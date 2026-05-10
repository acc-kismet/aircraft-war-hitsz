package com.airwar.core.engine;

import com.airwar.core.difficulty.DifficultyConfig;
import com.airwar.core.difficulty.DifficultyLevel;
import com.airwar.core.config.GameConstants;
import com.airwar.core.effect.EffectScheduler;
import com.airwar.core.effect.TimedEffect;
import com.airwar.core.model.aircraft.HeroAircraft;
import com.airwar.core.model.bullet.BaseBullet;
import com.airwar.core.spawn.EnemySpawner;
import com.airwar.core.strategy.CircularShootStrategy;
import com.airwar.core.strategy.StraightShootStrategy;

import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

public final class GameEngine {

    private static final Consumer<String> NOOP_HOOK = ignored -> { };

    private final EffectScheduler effectScheduler;
    private final DifficultyConfig difficultyConfig;
    private final EnemySpawner enemySpawner;
    private final HeroAircraft hero;
    private final Random random = new Random(20260407L);
    private final List<EntityState> heroBullets = new ArrayList<>();
    private final List<EntityState> enemyBullets = new ArrayList<>();
    private final List<EntityState> enemies = new ArrayList<>();
    private final List<EntityState> props = new ArrayList<>();
    private final List<EntityState> explosions = new ArrayList<>();

    private Consumer<String> debugHook = NOOP_HOOK;
    private int score;
    private int heroTargetX = GameConstants.LOGICAL_WIDTH / 2;
    private int heroTargetY = GameConstants.LOGICAL_HEIGHT - 140;
    private String heroShootMode = "STRAIGHT";
    private int heroHp = GameConstants.HERO_MAX_HP;
    private boolean gameOver;
    private boolean bossActive;

    private long heroShootCooldownMs;
    private long enemySpawnCooldownMs;
    private long enemyShootCooldownMs;
    private int heroShotEvents;
    private int hitEvents;
    private int explosionEvents;
    private int supplyEvents;
    private int gameOverEvents;

    private GameEngine(DifficultyLevel level) {
        DifficultyLevel safeLevel = Objects.requireNonNull(level, "level must not be null");
        DifficultyConfig config = DifficultyConfig.of(safeLevel);
        this.difficultyConfig = config;
        this.effectScheduler = new EffectScheduler();
        this.enemySpawner = new EnemySpawner(config);
        this.hero = new HeroAircraft(heroTargetX, heroTargetY, 0, 0, GameConstants.HERO_MAX_HP);
    }

    public static GameEngine create(DifficultyLevel level) {
        return new GameEngine(level);
    }

    public void setDebugHook(Consumer<String> hook) {
        this.debugHook = hook == null ? NOOP_HOOK : hook;
    }

    public void tick(long deltaMs) {
        if (deltaMs < 0) {
            throw new IllegalArgumentException("deltaMs must be non-negative");
        }

        phase("input");
        updateHeroAnchor();
        phase("update");
        updateBattle(deltaMs);
        phase("collision");
        handleCollisions();

        phase("spawn");
        int previousBossCount = enemySpawner.getBossCount();
        enemySpawner.update(score);
        if (enemySpawner.getBossCount() > previousBossCount) {
            spawnBoss();
        }

        phase("cleanup");
        cleanupEntities();

        effectScheduler.update(deltaMs);
    }

    public GameStateSnapshot getSnapshot() {
        return new GameStateSnapshot(
                enemySpawner.getBossCount(),
                heroTargetX,
                heroTargetY,
                score,
                heroHp,
                bossActive,
                heroShotEvents,
                hitEvents,
                explosionEvents,
                supplyEvents,
                gameOverEvents,
                mapToSnapshot(heroBullets),
                mapToSnapshot(enemyBullets),
                mapToSnapshot(enemies),
                mapToSnapshot(props),
                mapToSnapshot(explosions)
        );
    }

    public void setScore(int score) {
        if (score < 0) {
            throw new IllegalArgumentException("score must be non-negative");
        }
        this.score = score;
    }

    public void setHeroTarget(int x, int y) {
        this.heroTargetX = x;
        this.heroTargetY = y;
    }

    public void debugGrantSuperBullet(long durationMs) {
        if (durationMs <= 0L) {
            throw new IllegalArgumentException("durationMs must be positive");
        }
        effectScheduler.add(new TimedEffect(
                durationMs,
                () -> {
                    hero.setShootStrategy(new CircularShootStrategy(12, 7));
                    heroShootMode = "CIRCULAR";
                },
                () -> {
                    hero.setShootStrategy(new StraightShootStrategy(1));
                    heroShootMode = "STRAIGHT";
                }
        ));
    }

    public String getHeroShootMode() {
        return heroShootMode;
    }

    public static int scoreForEnemyType(String enemyType) {
        if ("boss".equals(enemyType)) {
            return 50;
        }
        if ("elite".equals(enemyType)) {
            return 20;
        }
        return 10;
    }

    private void phase(String name) {
        debugHook.accept(name);
    }

    private void updateHeroAnchor() {
        hero.setLocation(heroTargetX, heroTargetY);
    }

    private void updateBattle(long deltaMs) {
        if (gameOver) {
            return;
        }

        heroShootCooldownMs += deltaMs;
        enemySpawnCooldownMs += deltaMs;
        enemyShootCooldownMs += deltaMs;

        if (heroShootCooldownMs >= 220L) {
            spawnHeroBullet();
            heroShootCooldownMs = 0L;
        }

        if (enemySpawnCooldownMs >= difficultyConfig.enemySpawnIntervalMs()) {
            spawnMobEnemy();
            enemySpawnCooldownMs = 0L;
        }

        if (enemyShootCooldownMs >= difficultyConfig.enemyShootIntervalMs()) {
            spawnEnemyBullets();
            enemyShootCooldownMs = 0L;
        }

        for (EntityState bullet : heroBullets) {
            bullet.x += bullet.vx;
            bullet.y += bullet.vy;
        }
        for (EntityState bullet : enemyBullets) {
            bullet.x += bullet.vx;
            bullet.y += bullet.vy;
        }
        for (EntityState enemy : enemies) {
            enemy.x += enemy.vx;
            enemy.y += enemy.vy;
        }
    }

    private void handleCollisions() {
        for (EntityState heroBullet : heroBullets) {
            if (!heroBullet.active) {
                continue;
            }
            for (EntityState enemy : enemies) {
                if (!enemy.active) {
                    continue;
                }
                if (collides(heroBullet, enemy, 26)) {
                    heroBullet.active = false;
                    enemy.hp -= 1;
                    hitEvents++;
                    if (enemy.hp <= 0) {
                        enemy.active = false;
                        score += scoreForEnemyType(enemy.type);
                        explosionEvents++;
                        spawnExplosion(enemy.x, enemy.y, enemy.type.equals("boss") ? 500L : 280L, "explosion_enemy");
                        maybeDropProp(enemy.x, enemy.y, enemy.type);
                        if (enemy.type.equals("boss")) {
                            bossActive = false;
                        }
                    }
                    break;
                }
            }
        }

        for (EntityState enemyBullet : enemyBullets) {
            if (!enemyBullet.active) {
                continue;
            }
            if (Math.abs(enemyBullet.x - heroTargetX) < 24 && Math.abs(enemyBullet.y - heroTargetY) < 24) {
                enemyBullet.active = false;
                heroHp = Math.max(0, heroHp - difficultyConfig.enemyBulletDamage());
                hitEvents++;
                if (heroHp == 0 && !gameOver) {
                    gameOver = true;
                    gameOverEvents++;
                }
            }
        }

        for (EntityState enemy : enemies) {
            if (!enemy.active) {
                continue;
            }
            if (Math.abs(enemy.x - heroTargetX) < 28 && Math.abs(enemy.y - heroTargetY) < 28) {
                enemy.active = false;
                int collisionDamage = "boss".equals(enemy.type)
                        ? difficultyConfig.enemyCollisionDamage() * 2
                        : difficultyConfig.enemyCollisionDamage();
                heroHp = Math.max(0, heroHp - collisionDamage);
                hitEvents++;
                explosionEvents++;
                spawnExplosion(enemy.x, enemy.y, 320L, "explosion_collision");
                if ("boss".equals(enemy.type)) {
                    bossActive = false;
                }
                if (heroHp == 0 && !gameOver) {
                    gameOver = true;
                    gameOverEvents++;
                }
            }
        }

        for (EntityState prop : props) {
            if (!prop.active) {
                continue;
            }
            if (Math.abs(prop.x - heroTargetX) < 26 && Math.abs(prop.y - heroTargetY) < 26) {
                prop.active = false;
                supplyEvents++;
                applyProp(prop.type);
            }
        }
    }

    private void cleanupEntities() {
        heroBullets.removeIf(b -> !b.active || b.y < -20 || b.y > GameConstants.LOGICAL_HEIGHT + 20);
        enemyBullets.removeIf(b -> !b.active || b.y < -20 || b.y > GameConstants.LOGICAL_HEIGHT + 20);
        enemies.removeIf(e -> !e.active || e.y > GameConstants.LOGICAL_HEIGHT + 60);
        props.removeIf(p -> !p.active || p.y > GameConstants.LOGICAL_HEIGHT + 60);
        explosions.removeIf(e -> !e.active);
        for (EntityState prop : props) {
            prop.y += prop.vy;
        }
        for (EntityState explosion : explosions) {
            if (System.currentTimeMillis() >= explosion.expireAtMs) {
                explosion.active = false;
            }
        }
        bossActive = enemies.stream().anyMatch(e -> e.active && e.type.equals("boss"));
    }

    private void spawnHeroBullet() {
        List<BaseBullet> bullets = hero.shoot();
        String bulletType = "CIRCULAR".equals(heroShootMode) ? "hero_bullet_super" : "hero_bullet";
        for (BaseBullet bullet : bullets) {
            heroBullets.add(new EntityState(
                    bullet.getLocationX(),
                    bullet.getLocationY(),
                    bullet.getSpeedX(),
                    bullet.getSpeedY(),
                    1,
                    bulletType
            ));
        }
        heroShotEvents++;
    }

    private void spawnMobEnemy() {
        int x = 40 + random.nextInt(Math.max(1, GameConstants.LOGICAL_WIDTH - 80));
        enemies.add(new EntityState(x, -30, 0, 4, difficultyConfig.mobEnemyHp(), "mob"));
    }

    private void spawnBoss() {
        enemies.add(new EntityState(GameConstants.LOGICAL_WIDTH / 2, 90, 0, 1, 16, "boss"));
        bossActive = true;
    }

    private void spawnEnemyBullets() {
        for (EntityState enemy : enemies) {
            if (!enemy.active) {
                continue;
            }
            enemyBullets.add(new EntityState(enemy.x, enemy.y + 20, 0, 8, 1, "enemy_bullet"));
        }
    }

    private static boolean collides(EntityState a, EntityState b, int threshold) {
        return Math.abs(a.x - b.x) <= threshold && Math.abs(a.y - b.y) <= threshold;
    }

    private static List<GameStateSnapshot.EntitySnapshot> mapToSnapshot(List<EntityState> states) {
        List<GameStateSnapshot.EntitySnapshot> list = new ArrayList<>(states.size());
        for (EntityState state : states) {
            if (state.active) {
                list.add(new GameStateSnapshot.EntitySnapshot(state.x, state.y, state.type));
            }
        }
        return list;
    }

    private void maybeDropProp(int x, int y, String enemyType) {
        int chance = "boss".equals(enemyType) ? 100 : difficultyConfig.propDropChancePercent();
        if (random.nextInt(100) >= chance) {
            return;
        }

        String type;
        int r = random.nextInt(100);
        if (r < 45) {
            type = "prop_bullet";
        } else if (r < 75) {
            type = "prop_blood";
        } else {
            type = "prop_bomb";
        }
        props.add(new EntityState(x, y, 0, 4, 1, type));
    }

    private void applyProp(String type) {
        switch (type) {
            case "prop_blood" -> heroHp = Math.min(GameConstants.HERO_MAX_HP, heroHp + 20);
            case "prop_bomb" -> {
                for (EntityState enemy : enemies) {
                    if (enemy.active && !"boss".equals(enemy.type)) {
                        enemy.active = false;
                        explosionEvents++;
                        spawnExplosion(enemy.x, enemy.y, 220L, "explosion_enemy");
                        score += scoreForEnemyType(enemy.type);
                    }
                }
                enemyBullets.clear();
            }
            case "prop_bullet" -> debugGrantSuperBullet(3000);
            default -> {
            }
        }
    }

    private void spawnExplosion(int x, int y, long durationMs, String type) {
        EntityState explosion = new EntityState(x, y, 0, 0, 1, type);
        explosion.expireAtMs = System.currentTimeMillis() + durationMs;
        explosions.add(explosion);
    }

    private static final class EntityState {
        private int x;
        private int y;
        private final int vx;
        private final int vy;
        private int hp;
        private final String type;
        private boolean active = true;
        private long expireAtMs = Long.MAX_VALUE;

        private EntityState(int x, int y, int vx, int vy, int hp, String type) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.hp = hp;
            this.type = type;
        }
    }
}
