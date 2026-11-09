package com.smbplayer.tv;
public final class VideoRecoveryCheck {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  VideoRecovery r=new VideoRecovery();
  r.sample(0,10,10,5000,true,false);check(r.sample(1500,10,40,6500,true,false)==VideoRecovery.Action.STALLED,"No continuous freeze detection without Buffering");
  check(r.sample(2000,10,50,7000,true,false)==VideoRecovery.Action.RESYNC,"Freeze did not resync");r.ready(2100,10,50,5000);check(r.sample(6100,10,80,9000,true,false)==VideoRecovery.Action.REOPEN,"Persistent freeze did not rebuild");
  r.expect(7000,10,10,20000);check(r.sample(7250,20,20,20250,true,false)==VideoRecovery.Action.RECOVERED,"Seek without Buffering did not recover");
  r.expect(8000,100,100,30000);check(r.sample(8250,1,1,10000,true,false)==VideoRecovery.Action.NONE,"Backward seek/reset aborted recovery");check(r.sample(8500,5,5,10250,true,false)==VideoRecovery.Action.RECOVERED,"Backward seek did not recover");
  r.expect(9000,0,0,10000);check(r.sample(10000,0,0,10000,true,true)==VideoRecovery.Action.NONE&&!r.active(),"Paused playback triggered recovery");r.expect(11000,0,0,10000);check(r.sample(13000,0,10,12000,true,false)==VideoRecovery.Action.RESYNC,"Resume without Buffering missed freeze");
  r.begin(14000);check(r.sample(15000,0,0,0,true,false)==VideoRecovery.Action.NONE,"Network wait caused premature resync");check(r.sample(60000,0,0,0,true,false)==VideoRecovery.Action.FAILED,"Network wait unbounded");
  r.expect(70000,0,0,0);check(r.sample(72000,0,10,2000,false,false)==VideoRecovery.Action.REOPEN,"Live video was sought instead of reconnected");r.ready(72500,0,10,0);check(r.sample(76500,0,20,4000,false,false)==VideoRecovery.Action.FAILED,"Repeated live recovery not bounded");
  r.expect(80000,0,0,0);r.ready(81900,0,0,0);check(r.sample(82000,0,10,2000,true,false)==VideoRecovery.Action.RESYNC,"Duplicate ready reset timeout");
  System.out.println("Continuous freeze, seek, backward seek, pause/resume, network wait, live and bounded escalation passed");
 }
}
