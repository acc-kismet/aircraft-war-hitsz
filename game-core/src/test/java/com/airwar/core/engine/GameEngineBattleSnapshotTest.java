package com.airwar.core.engine;

import com.airwar.core.difficulty.DifficultyLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEngineBattleSnapshotTest {

    @Test
    void snapshotContainsBattleFieldsWithSafeDefaults() {
        GameEngine engine = GameEngine.create(DifficultyLevel.NORMAL);

        GameStateSnapshot snapshot = engine.getSnapshot();

        assertEquals(0, snapshot.score());
        assertEquals(100, snapshot.heroHp());
        assertFalse(snapshot.bossActive());
        assertTrue(snapshot.heroBullets().isEmpty());
        assertTrue(snapshot.enemyBullets().isEmpty());
        assertTrue(snapshot.enemies().isEmpty());
        assertTrue(snapshot.props().isEmpty());
        assertTrue(snapshot.explosions().isEmpty());
    }

    @Test
    void tickingProducesBulletsAndEnemies() {
        GameEngine engine = GameEngine.create(DifficultyLevel.NORMAL);

        for (int i = 0; i < 30; i++) {
            engine.tick(40);
        }

        GameStateSnapshot snapshot = engine.getSnapshot();
        assertTrue(snapshot.heroBullets().size() > 0);
        assertTrue(snapshot.enemies().size() > 0);
        assertTrue(snapshot.heroShotEvents() > 0);
    }

    @Test
    void bossActivationFollowsThreshold() {
        GameEngine engine = GameEngine.create(DifficultyLevel.NORMAL);
        engine.setScore(220);
        engine.tick(40);

        GameStateSnapshot snapshot = engine.getSnapshot();
        assertTrue(snapshot.bossActive());
        assertEquals(1, snapshot.bossCount());
    }

    @Test
    void heroCollisionWithEnemyReducesHpAndRemovesEnemy() {
        GameEngine engine = GameEngine.create(DifficultyLevel.NORMAL);
        engine.setScore(220);
        engine.tick(40);

        engine.setHeroTarget(256, 90);
        engine.tick(40);

        GameStateSnapshot snapshot = engine.getSnapshot();
        assertTrue(snapshot.heroHp() < 100);
        assertFalse(snapshot.bossActive());
        assertTrue(snapshot.explosions().size() > 0);
    }

    @Test
    void bulletPropChangesHeroBulletPatternTemporarily() {
        GameEngine engine = GameEngine.create(DifficultyLevel.NORMAL);
        engine.debugGrantSuperBullet(500);

        for (int i = 0; i < 8; i++) {
            engine.tick(40);
        }
        GameStateSnapshot during = engine.getSnapshot();
        assertTrue(during.heroBullets().stream().anyMatch(b -> "hero_bullet_super".equals(b.type())));

        engine.tick(600);
        for (int i = 0; i < 8; i++) {
            engine.tick(40);
        }
        GameStateSnapshot after = engine.getSnapshot();
        assertTrue(after.heroBullets().stream().anyMatch(b -> "hero_bullet".equals(b.type())));
    }

    @Test
    void localEnemyScoreRulesMatchAuthoritativeMultiplayerValues() {
        assertEquals(10, GameEngine.scoreForEnemyType("mob"));
        assertEquals(20, GameEngine.scoreForEnemyType("elite"));
        assertEquals(50, GameEngine.scoreForEnemyType("boss"));
        assertEquals(10, GameEngine.scoreForEnemyType("unknown"));
    }
}
