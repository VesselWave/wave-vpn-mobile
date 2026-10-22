// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpnprobe;
import android.app.*;
import android.os.*;
import android.content.*;
import android.net.*;
import android.widget.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
public class Probe extends Activity {
 static ScheduledExecutorService pool;
 static File output;
 static String phase="connected";
 static synchronized void record(String kind, String result) {
  try(FileWriter w=new FileWriter(output,true)) { w.write(System.currentTimeMillis()+"\t"+phase+"\t"+kind+"\t"+result.replace('\n',' ')+"\n"); } catch(Exception e){}
 }
 interface Check {String run() throws Exception;}
 void schedule(String kind, Check check, int seconds) {
  pool.scheduleWithFixedDelay(()->{try{record(kind,"OK "+check.run());}catch(Exception e){record(kind,"FAIL "+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage()));}},0,seconds,TimeUnit.SECONDS);
 }
 static String fetch(String value) throws Exception { return fetch(value,null); }
 static String fetch(String value,String accept) throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL(value).openConnection();
  c.setConnectTimeout(2500);c.setReadTimeout(2500);c.setUseCaches(false);c.setRequestProperty("Connection","close");
  if(accept!=null)c.setRequestProperty("Accept",accept);
  try(InputStream in=c.getInputStream()) {
   ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;
   while((n=in.read(b))!=-1){if(out.size()+n>16384)throw new IOException("Probe response too large");out.write(b,0,n);}
   return out.toString("UTF-8").trim();
  } finally{c.disconnect();}
 }
 static final String marker="waveqa-20261022";
 static void rawDns(String address) throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  out.write(new byte[]{0x51,0x53,1,0,0,1,0,0,0,0,0,0});
  for(String label:(marker+".example.com").split("\\.")){byte[] v=label.getBytes("US-ASCII");out.write(v.length);out.write(v);}
  out.write(new byte[]{0,0,1,0,1});byte[] packet=out.toByteArray();
  try(DatagramSocket socket=new DatagramSocket()){socket.send(new DatagramPacket(packet,packet.length,InetAddress.getByName(address),53));}
 }
 static String tcp(String address,int port) throws Exception {
  try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(InetAddress.getByName(address),port),350);return "connected";}
 }
 Network underlying() throws Exception {
  ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
  for(Network network:manager.getAllNetworks()) {
   NetworkCapabilities caps=manager.getNetworkCapabilities(network);
   if(caps!=null&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return network;
  }
  throw new IOException("No underlying network available to probe");
 }
 String boundTcp(String address) throws Exception {
  try(Socket socket=underlying().getSocketFactory().createSocket()) {
   socket.connect(new InetSocketAddress(InetAddress.getByName(address),443),350);return "connected";
  }
 }
 public void onCreate(Bundle b) {
  super.onCreate(b);startForegroundService(new Intent(this,KeepAlive.class));output=new File(getFilesDir(),"results.tsv");
  phase=getIntent().getStringExtra("phase");if(phase==null)phase="connected";
  TextView t=new TextView(this);t.setText("Wave independent VPN probe\nPhase: "+phase+"\nResults remain in private app storage.");setContentView(t);
  if(pool!=null)pool.shutdownNow();pool=Executors.newScheduledThreadPool(12);
  schedule("ipv4",()->fetch("https://api4.ipify.org"),2);
  schedule("ipv6",()->fetch("https://api6.ipify.org"),2);
  schedule("direct4",()->fetch("https://1.1.1.1/cdn-cgi/trace"),2);
  schedule("doh",()->{
   String name="probe-doh-"+System.nanoTime()+".example.com";
   String response=fetch("https://cloudflare-dns.com/dns-query?name="+name+"&type=A","application/dns-json");
   org.json.JSONObject answer=new org.json.JSONObject(response);
   int status=answer.getInt("Status");
   if((status!=0&&status!=3)||!answer.getJSONArray("Question").getJSONObject(0).getString("name").replaceAll("\\.$","").equals(name))throw new IOException("Unexpected DoH response");
   return "DNS response status="+status+" for fresh query; endpoints="+java.util.Arrays.toString(InetAddress.getAllByName("cloudflare-dns.com"));
  },2);
  schedule("dns",()->{String name="probe-"+System.nanoTime()+".1-1-1-1.sslip.io";return InetAddress.getByName(name).getHostAddress();},2);
  pool.scheduleWithFixedDelay(()->{try{rawDns("1.1.1.1");}catch(Exception e){}},0,200,TimeUnit.MILLISECONDS);
  pool.scheduleWithFixedDelay(()->{try{rawDns("2606:4700:4700::1111");}catch(Exception e){}},0,200,TimeUnit.MILLISECONDS);
  schedule("dot4",()->tcp("1.1.1.1",853),1);
  schedule("dot6",()->tcp("2606:4700:4700::1111",853),1);
  schedule("bound-dns",()->underlying().getAllByName("probe-bound-"+System.nanoTime()+".1-1-1-1.sslip.io")[0].getHostAddress(),2);
  schedule("bound-tcp4",()->boundTcp("1.1.1.1"),2);
  schedule("bound-tcp6",()->boundTcp("2606:4700:4700::1111"),2);
  schedule("route",()->{ConnectivityManager m=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=m.getActiveNetwork();return n==null?"none":String.valueOf(m.getNetworkCapabilities(n))+" "+String.valueOf(m.getLinkProperties(n));},2);
 }
}
