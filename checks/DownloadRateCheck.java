package com.smbplayer.tv;
public final class DownloadRateCheck {
 public static void main(String[] args){DownloadRate r=new DownloadRate();if(r.sample(0,0)!=0)throw new AssertionError();if(r.sample(1048576,1000)!=1048576)throw new AssertionError();r.sample(2097152,2000);r.sample(2097152,3000);r.sample(2097152,4000);if(r.sample(2097152,5000)!=0)throw new AssertionError("Stale speed");if(r.sample(0,6000)!=0)throw new AssertionError("Reset");if(!DownloadRate.format(2097152).equals("2.0 MB/s"))throw new AssertionError();System.out.println("Real-byte rate, rolling decay, reset and display units passed");}
}
