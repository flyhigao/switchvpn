package com.local.vpncommon;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * 防抖（settle）用的 Job：系统会在到点时临时唤醒/解冻本进程执行它。
 * 这样不需要进程一直活着，也不受 ColorOS 闹钟推迟（+2 天）的影响。
 */
public class SettleJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.i("VpnSwitch", "SettleJob start id=" + params.getJobId());
        Controller.firePending(this, new Controller.Callback() {
            @Override
            public void onResult(boolean ok, String message) {
                Log.i("VpnSwitch", "job result ok=" + ok + " msg=" + message);
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
