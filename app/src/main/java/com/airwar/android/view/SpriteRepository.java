package com.airwar.android.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;

import com.airwar.android.R;

import java.util.Locale;

public final class SpriteRepository {
    private final Paint textPaint;
    private Bitmap background;
    private final Bitmap hero;
    private final Bitmap mobEnemy;
    private final Bitmap bossEnemy;
    private final Bitmap heroBullet;
    private final Bitmap heroBulletSuper;
    private final Bitmap enemyBullet;
    private final Bitmap propBullet;
    private final Bitmap propBomb;
    private final Bitmap propBlood;
    private final Paint explosionPaint;

    public SpriteRepository(Context context) {
        background = decode(context, backgroundResForDifficulty("normal"));
        hero = decode(context, R.drawable.hero);
        mobEnemy = decode(context, R.drawable.mob);
        bossEnemy = decode(context, R.drawable.boss);
        heroBullet = decode(context, R.drawable.bullet_hero);
        heroBulletSuper = decode(context, R.drawable.prop_bullet_plus);
        enemyBullet = decode(context, R.drawable.bullet_enemy);
        propBullet = decode(context, R.drawable.prop_bullet);
        propBomb = decode(context, R.drawable.prop_bomb);
        propBlood = decode(context, R.drawable.prop_blood);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36f);

        explosionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        explosionPaint.setColor(Color.argb(180, 255, 140, 40));
    }

    public Bitmap background() {
        return background;
    }

    public void setDifficulty(String difficulty, Context context) {
        background = decode(context, backgroundResForDifficulty(difficulty));
    }

    public Bitmap hero() {
        return hero;
    }

    public Bitmap mobEnemy() {
        return mobEnemy;
    }

    public Bitmap bossEnemy() {
        return bossEnemy;
    }

    public Bitmap heroBullet() {
        return heroBullet;
    }

    public Bitmap heroBulletSuper() {
        return heroBulletSuper;
    }

    public Bitmap enemyBullet() {
        return enemyBullet;
    }

    public Bitmap propBullet() {
        return propBullet;
    }

    public Bitmap propBomb() {
        return propBomb;
    }

    public Bitmap propBlood() {
        return propBlood;
    }

    public Paint explosionPaint() {
        return explosionPaint;
    }

    public Paint textPaint() {
        return textPaint;
    }

    static int backgroundResForDifficulty(String difficulty) {
        if (difficulty == null) {
            return R.drawable.bg3;
        }
        return switch (difficulty.toLowerCase(Locale.ROOT)) {
            case "easy" -> R.drawable.bg2;
            case "hard" -> R.drawable.bg5;
            default -> R.drawable.bg3;
        };
    }

    private static Bitmap decode(Context context, int resId) {
        return BitmapFactory.decodeResource(context.getResources(), resId);
    }
}
