package com.xxl.job.admin.core.thread;

import com.xxl.job.admin.core.conf.XxlJobAdminConfig;
import com.xxl.job.admin.core.cron.CronExpression;
import com.xxl.job.admin.core.model.XxlJobInfo;
import com.xxl.job.admin.core.scheduler.MisfireStrategyEnum;
import com.xxl.job.admin.core.scheduler.ScheduleTypeEnum;
import com.xxl.job.admin.core.trigger.TriggerTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author xuxueli 2019-05-21
 * * 任务调度器
 *  * 工作流程：
 *  * 周期性的遍历所有的jobInfo这个表，通过数据库的行锁和事务一致性，通过for update 来保证多个调度中心集群在同一时间内只有一个调度中心在调度任务
 *  * 周期性的遍历所有的jobInfo这个表，读取触发时间小于nowtime+5s这个时间之前的所有任务，然后进行引入以下触发机制判断
 *  * 三种触发任务机制： 1）nowtime-TriggerNextTime()>PRE_READ_MS(5s) 既超过有效误差内，则查看当前任务的失效调度策略，若为立即重试一次，则立即触发调度任务，且触发类型为misfire
 *  *                  2)nowtime-TriggerNextTime()<PRE_READ_MS(5s) 既没有超过有效误差，则立即调度调度任务
 *  *                  3)nowtime<TriggerNextTime() 则说明这个任务马上就要触发了，放到一个时间轮上（https://blog.csdn.net/zalu9810/article/details/113396131），
 *  *                    随后将快要触发的任务放到时间轮上，时间轮由key(将要触发的时间s)，value(在当前触发s的所有任务id集合)，然后更新这个任务的下一次触发时间
 *  * 这个时间轮的任务遍历交由第二个线程处理ringThread，周期在1s之内周期的扫描这个时间轮，然后执行调度任务
 *
 * ————————————————
 */
public class JobScheduleHelper {
    private static Logger logger = LoggerFactory.getLogger(JobScheduleHelper.class);

    private static JobScheduleHelper instance = new JobScheduleHelper();
    public static JobScheduleHelper getInstance(){
        System.out.println("[JobScheduleHelper]==========>被实例化了");
        return instance;
    }

    public static final long PRE_READ_MS = 5000;    // pre read

    private Thread scheduleThread;
    private Thread ringThread;
    private volatile boolean scheduleThreadToStop = false;
    private volatile boolean ringThreadToStop = false;
    private volatile static Map<Integer, List<Integer>> ringData = new ConcurrentHashMap<>();

    public void start(){

        // schedule thread
        scheduleThread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {
                    TimeUnit.MILLISECONDS.sleep(5000 - System.currentTimeMillis()%1000 );
                } catch (InterruptedException e) {
                    if (!scheduleThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>> init xxl-job admin scheduler success.");

                // pre-read count: treadpool-size * trigger-qps (each trigger cost 50ms, qps = 1000/50 = 20)
                // 这里默认么个定时任务线程执行耗时50毫秒，每秒1000毫秒，可以执行20个任务，快线程池默认是200个最大线程数，慢线程默认是100个最大线程数，
                // 所以当线程数拉满的情况下，每秒钟可以处理任务数是：（100+200）*20 = 6000 ；所以这里的  preReadCount 表示预读数（默认最大：6000）
                // 如果想要提高并发性，通过修改快慢线程池的最大线程数这个参数调节
                int preReadCount = (XxlJobAdminConfig.getAdminConfig().getTriggerPoolFastMax() + XxlJobAdminConfig.getAdminConfig().getTriggerPoolSlowMax()) * 20;
                logger.info("==========================preReadCount ={}",preReadCount);

                while (!scheduleThreadToStop) {

                    // Scan Job
                    long start = System.currentTimeMillis();

                    Connection conn = null;
                    Boolean connAutoCommit = null;
                    PreparedStatement preparedStatement = null;

                    boolean preReadSuc = true;
                    try {

                        conn = XxlJobAdminConfig.getAdminConfig().getDataSource().getConnection();
                        connAutoCommit = conn.getAutoCommit();
                        conn.setAutoCommit(false);
                        // 利用数据库的行锁
                        preparedStatement = conn.prepareStatement(  "select * from xxl_job_lock where lock_name = 'schedule_lock' for update" );
                        preparedStatement.execute();

                        // tx start

                        // 1、预读数据
                        long nowTime = System.currentTimeMillis();
                        // 由于分析了最多可以执行6000个任务，所以这里在去查任务表的时候，最多去查出来6000条满足条件的
                        // 条件：下次执行时间 小于等于 （当前时间 + 5秒） 并且  执行状态是  正在运行的状态
                        List<XxlJobInfo> scheduleList = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleJobQuery(nowTime + PRE_READ_MS, preReadCount);
                        if (scheduleList!=null && scheduleList.size()>0) {
                            // 2、push time-ring
                            for (XxlJobInfo jobInfo: scheduleList) {

                                // time-ring jump
                                //如果服务宕机了，或者重启等等，导致超过了调度周期(5秒的调度周期)，也就是本来由时间上的上一次或上很多次调度触发的数据被本次调度查到了，
                                // 这就可能代表着可能中间存在多次调度未触发，而按照周期性一次一次计算下次预期调度时间，那这次调度完了计算出来的下次调度还是在当前时间以前，
                                // 例如调度周期1分钟调度一次，宕机5分钟了，现在查到的预期调度时间为5分钟前，如果直接调度成功会重复调度5次当前时间以前的任务，这里直接pass并计算下一次调度时间，
                                // 但是计算下一次调度时间也是传入当前时间，直接修正预期下次调度时间为当前时间之后，因为调度时间周期为 5秒，所以会+ PRE_READ_MS 判断，如果是一次性的调度则会补偿这次调度
                                if (nowTime > jobInfo.getTriggerNextTime() + PRE_READ_MS) {
                                    // 2.1、trigger-expire > 5s：pass && make next-trigger-time
                                    logger.warn(">>>>>>>>>>> xxl-job, schedule misfire, jobId = " + jobInfo.getId());

                                    // 拿到设置的 调度过期策略
                                    MisfireStrategyEnum misfireStrategyEnum = MisfireStrategyEnum.match(jobInfo.getMisfireStrategy(), MisfireStrategyEnum.DO_NOTHING);
                                    // 如果是忽略就不执行，如果是立刻执行一次，就立马调用执行一次该执行器
                                    if (MisfireStrategyEnum.FIRE_ONCE_NOW == misfireStrategyEnum) {
                                        // FIRE_ONCE_NOW 》 trigger
                                        JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.MISFIRE, -1, null, null, null);
                                        logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId() );
                                    }

                                    // 2、从当前时间算起，算出这个定时器下次应该执行时间
                                    refreshNextValidTime(jobInfo, new Date());

                                    // 当前的时间大于执行器下次的执行的时间，说明上次执行器可能遗漏了这个执行器
                                } else if (nowTime > jobInfo.getTriggerNextTime()) {
                                    // 2.2、trigger-expire < 5s：direct-trigger && make next-trigger-time

                                    // 1、trigger
                                    JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.CRON, -1, null, null, null);
                                    logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId() );

                                    // 2、从当前时间算起，算出这个定时器下次应该执行时间
                                    refreshNextValidTime(jobInfo, new Date());

                                    // next-trigger-time in 5s, pre-read again
                                    // 如果下次发送时间在当前时间之后5秒内，会进行第二次触发，放到另一个线程中执行触发逻辑
                                    if (jobInfo.getTriggerStatus()==1 && nowTime + PRE_READ_MS > jobInfo.getTriggerNextTime()) {

                                        // 1、make ring second
                                        // 这里算出的结果保持在 0 - 59之间
                                        int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                        // 2、将当前任务放进一个全局变量map中，让ringThread线程去执行
                                        pushTimeRing(ringSecond, jobInfo.getId());

                                        // 3、从当前时间算起，算出这个定时器下次应该执行时间
                                        refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

                                    }

                                    // 取5秒的直接触发 但是区别于上面是第一次触发
                                } else {
                                    // 2.3、trigger-pre-read：time-ring trigger && make next-trigger-time

                                    // 1、make ring second
                                    int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                    // 2、push time ring
                                    pushTimeRing(ringSecond, jobInfo.getId());

                                    // 3、从当前时间算起，算出这个定时器下次应该执行时间
                                    refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));

                                }

                            }

                            // 3、更新执行器，主要是更新 上次触发时间，下次触发时间，和当前执行器的状态
                            for (XxlJobInfo jobInfo: scheduleList) {
                                XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleUpdate(jobInfo);
                            }

                        } else {
                            preReadSuc = false;
                        }

                        // tx stop


                    } catch (Exception e) {
                        if (!scheduleThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread error:{}", e);
                        }
                    } finally {

                        // commit
                        if (conn != null) {
                            try {
                                conn.commit();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.setAutoCommit(connAutoCommit);
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                            try {
                                conn.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }

                        // close PreparedStatement
                        if (null != preparedStatement) {
                            try {
                                preparedStatement.close();
                            } catch (SQLException e) {
                                if (!scheduleThreadToStop) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }
                    }
                    long cost = System.currentTimeMillis()-start;


                    // Wait seconds, align second
                    if (cost < 1000) {  // scan-overtime, not wait
                        try {
                            // pre-read period: success > scan each second; fail > skip this period;
                            // 首先 System.currentTimeMillis()%1000 这里的取余，最大值是999毫秒，可以理解成极限的一秒
                            // 如果读到了数据 休眠 （1 - （System.currentTimeMillis()%1000））秒，这里最大休眠1秒，最小接近不休眠直接执行
                            // 因为读到了数据，不知道接下来还有没有数据，这里为了赶工确保满足条件的定时任务能快速被执行。
                            // 如果读不到数据，休眠 （5 - （System.currentTimeMillis()%1000））秒，这里最大休眠5秒，最小休眠4秒
                            // 因为已经读不到数据了，多休息一下，让定时器等到需要被执行的时间点
                            TimeUnit.MILLISECONDS.sleep((preReadSuc?1000:PRE_READ_MS) - System.currentTimeMillis()%1000);
                        } catch (InterruptedException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }

                }

                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread stop");
            }
        });
        scheduleThread.setDaemon(true);
        scheduleThread.setName("xxl-job, admin JobScheduleHelper#scheduleThread");
        scheduleThread.start();


        // ring thread
        ringThread = new Thread(new Runnable() {
            @Override
            public void run() {

                while (!ringThreadToStop) {

                    // align second
                    try {
                        // 上来休眠最大1秒，如果scheduleThread有执行任务，保证会向 ringData（map） 里面写
                        TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
                    } catch (InterruptedException e) {
                        if (!ringThreadToStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                    try {
                        // second data
                        // 从 ringData（map）里面拿到，
                        List<Integer> ringItemData = new ArrayList<>();
                        // 上面介绍过 这个map的key是在0-59之间，直接取当前的秒数（0-59）
                        int nowSecond = Calendar.getInstance().get(Calendar.SECOND);   // 避免处理耗时太长，跨过刻度，向前校验一个刻度；
                        for (int i = 0; i < 2; i++) {
                            // 循环两次从 ringData（map）中拿到两个时间点的执行器ID集合 list，然后赋值给 ringItemData
                            List<Integer> tmpData = ringData.remove( (nowSecond+60-i)%60 );
                            if (tmpData != null) {
                                ringItemData.addAll(tmpData);
                            }
                        }

                        // ring trigger
                        logger.debug(">>>>>>>>>>> xxl-job, time-ring beat : " + nowSecond + " = " + Arrays.asList(ringItemData) );
                        // 如果 ringItemData 集合不为空，说明有需要执行的执行器Id，就遍历执行里面Id对应的执行器
                        if (ringItemData.size() > 0) {
                            // do trigger
                            for (int jobId: ringItemData) {
                                // do trigger
                                JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null, null);
                            }
                            // clear
                            // 遍历完成后，将 ringItemData 置空，然后等待线程 下次执行
                            ringItemData.clear();
                        }
                    } catch (Exception e) {
                        if (!ringThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread error:{}", e);
                        }
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread stop");
            }
        });
        ringThread.setDaemon(true);
        ringThread.setName("xxl-job, admin JobScheduleHelper#ringThread");
        ringThread.start();
    }

    private void refreshNextValidTime(XxlJobInfo jobInfo, Date fromTime) throws Exception {
        Date nextValidTime = generateNextValidTime(jobInfo, fromTime);
        if (nextValidTime != null) {
            jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
            jobInfo.setTriggerNextTime(nextValidTime.getTime());
        } else {
            jobInfo.setTriggerStatus(0);
            jobInfo.setTriggerLastTime(0);
            jobInfo.setTriggerNextTime(0);
            logger.warn(">>>>>>>>>>> xxl-job, refreshNextValidTime fail for job: jobId={}, scheduleType={}, scheduleConf={}",
                    jobInfo.getId(), jobInfo.getScheduleType(), jobInfo.getScheduleConf());
        }
    }

    private void pushTimeRing(int ringSecond, int jobId){
        // push async ring
        List<Integer> ringItemData = ringData.get(ringSecond);
        if (ringItemData == null) {
            ringItemData = new ArrayList<Integer>();
            ringData.put(ringSecond, ringItemData);
        }
        ringItemData.add(jobId);

        logger.debug(">>>>>>>>>>> xxl-job, schedule push time-ring : " + ringSecond + " = " + Arrays.asList(ringItemData) );
    }

    public void toStop(){

        // 1、stop schedule
        scheduleThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);  // wait
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (scheduleThread.getState() != Thread.State.TERMINATED){
            // interrupt and wait
            scheduleThread.interrupt();
            try {
                scheduleThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // if has ring data
        boolean hasRingData = false;
        if (!ringData.isEmpty()) {
            for (int second : ringData.keySet()) {
                List<Integer> tmpData = ringData.get(second);
                if (tmpData!=null && tmpData.size()>0) {
                    hasRingData = true;
                    break;
                }
            }
        }
        if (hasRingData) {
            try {
                TimeUnit.SECONDS.sleep(8);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // stop ring (wait job-in-memory stop)
        ringThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (ringThread.getState() != Thread.State.TERMINATED){
            // interrupt and wait
            ringThread.interrupt();
            try {
                ringThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper stop");
    }


    // ---------------------- tools ----------------------
    public static Date generateNextValidTime(XxlJobInfo jobInfo, Date fromTime) throws Exception {
        ScheduleTypeEnum scheduleTypeEnum = ScheduleTypeEnum.match(jobInfo.getScheduleType(), null);
        if (ScheduleTypeEnum.CRON == scheduleTypeEnum) {
            Date nextValidTime = new CronExpression(jobInfo.getScheduleConf()).getNextValidTimeAfter(fromTime);
            return nextValidTime;
        } else if (ScheduleTypeEnum.FIX_RATE == scheduleTypeEnum /*|| ScheduleTypeEnum.FIX_DELAY == scheduleTypeEnum*/) {
            return new Date(fromTime.getTime() + Integer.valueOf(jobInfo.getScheduleConf())*1000 );
        }
        return null;
    }

}
