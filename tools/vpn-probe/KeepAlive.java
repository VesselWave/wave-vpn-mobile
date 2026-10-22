// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpnprobe;
import android.app.*;import android.content.*;import android.os.*;
public class KeepAlive extends Service {
 public IBinder onBind(Intent i){return null;}
 public int onStartCommand(Intent i,int f,int s){
  NotificationManager m=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
  m.createNotificationChannel(new NotificationChannel("probe","VPN test probe",NotificationManager.IMPORTANCE_LOW));
  startForeground(1,new Notification.Builder(this,"probe").setContentTitle("VPN verification running").setSmallIcon(android.R.drawable.ic_menu_info_details).build());
  return START_NOT_STICKY;
 }
}
