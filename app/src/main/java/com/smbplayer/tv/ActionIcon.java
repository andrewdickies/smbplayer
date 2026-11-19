package com.smbplayer.tv;

import android.graphics.*;
import android.graphics.drawable.Drawable;

final class ActionIcon extends Drawable {
    private final String name;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    ActionIcon(String name){this.name=name;p.setColor(0xffe8f8fa);p.setStrokeWidth(1.9f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
    private void line(Canvas c,float x,float y,float a,float b){c.drawLine(x,y,a,b,p);}
    private void path(Canvas c,float... points){
        Path path=new Path();path.moveTo(points[0],points[1]);
        for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
        if(p.getStyle()==Paint.Style.FILL)path.close();c.drawPath(path,p);
    }
    @Override public void draw(Canvas c){
        int save=c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
        p.setStyle(Paint.Style.STROKE);
        switch(name){
            case "plus":case "minus":
                line(c,5,12,19,12);if(name.equals("plus"))line(c,12,5,12,19);break;
            case "lock":
                c.drawRoundRect(5,10,19,21,2,2,p);c.drawArc(8,3,16,14,180,180,false,p);line(c,8,8,8,10);line(c,16,8,16,10);line(c,12,14,12,17);break;
            case "settings":
                c.drawCircle(12,12,7,p);c.drawCircle(12,12,3,p);
                for(int i=0;i<8;i++){line(c,12,2.5f,12,5);c.rotate(45,12,12);}break;
            case "speed":
                c.drawArc(3,4,21,22,155,230,false,p);
                line(c,12,13,16,8);c.drawCircle(12,13,1,p);line(c,8,19,16,19);break;
            case "sequence":
                for(int y=6;y<=18;y+=6)line(c,3,y,12,y);
                path(c,15,8,20,12,15,16);break;
            case "back":
                path(c,11,5,4,12,11,19);line(c,4,12,21,12);break;
            case "filter":path(c,3,5,21,5,14,13,14,19,10,21,10,13,3,5);break;
            case "fit":path(c,8,4,4,4,4,8);path(c,16,4,20,4,20,8);path(c,20,16,20,20,16,20);path(c,8,20,4,20,4,16);break;
            case "repeat":case "repeatOne":
                path(c,4,11,4,7,19,7,16,4);path(c,20,13,20,17,5,17,8,20);
                if(name.equals("repeatOne"))path(c,11,11,13,10,13,14);break;
            case "shuffle":path(c,4,7,8,7,16,17,20,17,17,14);path(c,4,17,8,17,16,7,20,7,17,4);break;
            case "target":c.drawCircle(12,12,7,p);c.drawCircle(12,12,2,p);break;
            case "pause":p.setStyle(Paint.Style.FILL);c.drawRect(6,4,10,20,p);c.drawRect(14,4,18,20,p);break;
            case "stop":p.setStyle(Paint.Style.FILL);c.drawRect(6,6,18,18,p);break;
            case "pip":
                c.drawRoundRect(3,5,21,19,1,1,p);
                p.setStyle(Paint.Style.FILL);c.drawRoundRect(12,12,19,17,1,1,p);break;
            case "more":p.setStyle(Paint.Style.FILL);for(int x=5;x<=19;x+=7)c.drawCircle(x,12,1.6f,p);break;
            case "music":
                path(c,9,17,9,6,19,3,19,14);p.setStyle(Paint.Style.FILL);c.drawOval(4,15,10,20,p);c.drawOval(14,12,20,17,p);break;
            case "photo":c.drawRect(3,4,21,20,p);c.drawCircle(15,9,2,p);path(c,4,17,9,11,15,18);break;
            case "previous":case "next":
                if(name.equals("previous")){c.translate(24,0);c.scale(-1,1);}
                p.setStyle(Paint.Style.FILL);path(c,5,4,16,12,5,20);c.drawRect(17,4,20,20,p);break;
            case "grid":
                for(int x=4;x<=14;x+=10)for(int y=4;y<=14;y+=10)c.drawRect(x,y,x+6,y+6,p);break;
            case "sort":path(c,5,6,8,3,11,6);line(c,8,3,8,21);line(c,13,6,20,6);line(c,13,12,18,12);line(c,13,18,16,18);break;
            case "list":for(int y=5;y<=19;y+=7){line(c,7,y,20,y);line(c,3,y,3,y);}break;
            default:p.setStyle(Paint.Style.FILL);path(c,7,4,20,12,7,20);
        }
        c.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
    @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
