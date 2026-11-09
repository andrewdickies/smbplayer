// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Bounded TV cards: five across when readable, otherwise two centered rows. */
final class HomeDashboard extends ViewGroup {
    private final List<Card> cards;
    private final int gap;
    private int cardWidth,cardHeight,rows,firstCount,groupTop,viewportHeight;

    HomeDashboard(Context context,List<Card> cards) {
        super(context);this.cards=cards;gap=dp(16);
        setFocusable(false);setClipChildren(false);setClipToPadding(false);
        setPadding(dp(4),dp(8),dp(4),dp(8));
        for(Card card:cards)addView(card);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    void setViewportHeight(int height){viewportHeight=height;}
    private int rowCount(int row){return row==0?firstCount:cards.size()-firstCount;}
    private int rowStart(int row){return row==0?0:firstCount;}
    private int leftFor(int index,int available){
        int row=index<firstCount?0:1,count=rowCount(row);
        return getPaddingLeft()+(available-count*cardWidth-(count-1)*gap)/2+(index-rowStart(row))*(cardWidth+gap);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),available=Math.max(1,width-getPaddingLeft()-getPaddingRight());
        int minCard=dp(160);
        for(Card card:cards)minCard=Math.max(minCard,(int)Math.ceil(card.label.getPaint().measureText(card.label.getText().toString()))+dp(28));
        firstCount=available>=cards.size()*minCard+(cards.size()-1)*gap?cards.size():3;
        rows=firstCount==cards.size()?1:2;
        cardWidth=Math.max(1,Math.min(dp(240),(available-(firstCount-1)*gap)/firstCount));
        cardHeight=rows==1?Math.max(dp(176),Math.min(dp(216),cardWidth))
                :Math.min(dp(176),Math.max(1,(viewportHeight-getPaddingTop()-getPaddingBottom()-gap)/2));
        // System font enlargement can require extra height; scrolling keeps every card reachable.
        for(Card card:cards){
            int lines=card.label.getPaint().measureText(card.label.getText().toString())>cardWidth-dp(24)?2:1;
            card.setCompact(rows>1);
            cardHeight=Math.max(cardHeight,dp(rows==1?64+16+32:56+12+24)+lines*card.label.getLineHeight());
        }
        int contentHeight=rows*cardHeight+(rows-1)*gap;
        int desired=contentHeight+getPaddingTop()+getPaddingBottom();
        int height=resolveSize(desired,heightSpec);setMeasuredDimension(width,height);
        groupTop=getPaddingTop()+Math.max(0,(height-getPaddingTop()-getPaddingBottom()-contentHeight)/2);
        for(Card card:cards)card.measure(MeasureSpec.makeMeasureSpec(cardWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(cardHeight,MeasureSpec.EXACTLY));
        for(int i=0;i<cards.size();i++){
            Card card=cards.get(i);int row=i<firstCount?0:1,start=rowStart(row),end=start+rowCount(row)-1;
            card.setNextFocusLeftId(cards.get(Math.max(start,i-1)).getId());
            card.setNextFocusRightId(cards.get(Math.min(end,i+1)).getId());
            card.setNextFocusUpId(row==0?card.getId():cards.get(nearest(i,0,available)).getId());
            card.setNextFocusDownId(row==rows-1?card.getId():cards.get(nearest(i,1,available)).getId());
        }
    }
    private int nearest(int from,int targetRow,int available){
        int center=leftFor(from,available)+cardWidth/2,start=rowStart(targetRow),best=start,distance=Integer.MAX_VALUE;
        for(int i=start;i<start+rowCount(targetRow);i++){int delta=Math.abs(leftFor(i,available)+cardWidth/2-center);if(delta<distance){distance=delta;best=i;}}
        return best;
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        int available=getMeasuredWidth()-getPaddingLeft()-getPaddingRight();
        for(int i=0;i<cards.size();i++){int x=leftFor(i,available),y=groupTop+(i<firstCount?0:cardHeight+gap);cards.get(i).layout(x,y,x+cardWidth,y+cardHeight);}
    }

    static final class Card extends FrameLayout {
        final TextView label;
        final ImageView icon;
        final LinearLayout content;
        Card(Context context,String title,int resource,Drawable background,Runnable action){
            super(context);setId(View.generateViewId());setBackground(background);
            setFocusable(true);setFocusableInTouchMode(true);setClickable(true);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);setContentDescription(title);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.CENTER);
            content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            int pad=dp(12);setPadding(pad,pad,pad,pad);
            icon=new ImageView(context);icon.setImageResource(resource);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon,new LinearLayout.LayoutParams(dp(64),dp(64)));
            label=new TextView(context);label.setText(title);label.setTextSize(24);label.setTextColor(0xffffffff);
            label.setTypeface(Typeface.DEFAULT_BOLD);label.setGravity(Gravity.CENTER);label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setIncludeFontPadding(false);
            LinearLayout.LayoutParams labelSpace=new LinearLayout.LayoutParams(-1,-2);labelSpace.topMargin=dp(16);content.addView(label,labelSpace);
            addView(content,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
            setOnClickListener(v->action.run());
        }
        void setCompact(boolean compact){
            int size=dp(compact?56:64),gap=dp(compact?12:16);
            LinearLayout.LayoutParams image=(LinearLayout.LayoutParams)icon.getLayoutParams();
            if(image.width!=size){image.width=size;image.height=size;icon.setLayoutParams(image);}
            LinearLayout.LayoutParams title=(LinearLayout.LayoutParams)label.getLayoutParams();
            if(title.topMargin!=gap){title.topMargin=gap;label.setLayoutParams(title);}
        }
        private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
        @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)requestFocusFromTouch();return super.onTouchEvent(event);}
        private boolean confirm(int key){return key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER||key==KeyEvent.KEYCODE_BUTTON_A;}
        @Override public boolean onKeyDown(int key,KeyEvent event){if(confirm(key)){if(event.getRepeatCount()==0)setPressed(true);return true;}return super.onKeyDown(key,event);}
        @Override public boolean onKeyUp(int key,KeyEvent event){if(confirm(key)){boolean activate=isPressed()&&!event.isCanceled();setPressed(false);if(activate)performClick();return true;}return super.onKeyUp(key,event);}
        @Override public CharSequence getAccessibilityClassName(){return Button.class.getName();}
    }
}
