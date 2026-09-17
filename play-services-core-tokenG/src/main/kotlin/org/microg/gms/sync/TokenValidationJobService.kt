/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TokenValidationJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "TokenValidationJobService started")
        scope.launch {
            try {
                TokenValidationRunner.validateAll(applicationContext, force = false)
            } catch (e: Exception) {
                Log.w(TAG, "Error in background validation job", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "TokenValidationJobService stopped by system")
        scope.coroutineContext.cancelChildren()
        return true
    }

    companion object {
        private const val TAG = "TokenValidationJob"
        const val JOB_ID = 0x70C4
        private val PERIOD_MS = TimeUnit.HOURS.toMillis(12)
        private val FLEX_MS = TimeUnit.HOURS.toMillis(2)

        fun schedule(context: Context) {
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val isScheduled = js.allPendingJobs.any { it.id == JOB_ID }
                if (isScheduled) {
                    return
                }

                val component = ComponentName(context, TokenValidationJobService::class.java)
                val builder = JobInfo.Builder(JOB_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setPeriodic(PERIOD_MS, FLEX_MS)
                } else {
                    builder.setPeriodic(PERIOD_MS)
                }

                val result = js.schedule(builder.build())
                Log.d(TAG, "Scheduled TokenValidationJobService (result=$result)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule TokenValidationJobService", e)
            }
        }
    }
}
