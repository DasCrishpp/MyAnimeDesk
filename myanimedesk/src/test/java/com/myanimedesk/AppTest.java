package com.myanimedesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit test for simple App.
 */
public class AppTest 
{
    /**
     * Rigorous Test :-)
     */
    @Test
    public void shouldAnswerWithTrue()
    {
        assertTrue( true );
    }

    @Test
    public void shouldToggleFavoriteAndKeepItInTheLibrary()
    {
        AnimeListManager manager = new AnimeListManager();
        Anime anime = new Anime();
        anime.id = 42;
        anime.title = "Test Anime";

        assertTrue(manager.toggleFavorite(anime));
        assertEquals(1, manager.all().size());
        assertEquals(1, manager.favorites().size());

        assertFalse(manager.toggleFavorite(anime));
        assertEquals(0, manager.favorites().size());
    }
}
