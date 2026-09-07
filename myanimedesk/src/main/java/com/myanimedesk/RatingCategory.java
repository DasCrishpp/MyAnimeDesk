package com.myanimedesk;

import java.util.Map;

public enum RatingCategory {
    CHARACTER_DESIGN("Character design", "Character design", "Aspetto, originalità e riconoscibilità dei personaggi.", "Appearance, originality and recognisability of the characters."),
    ANIMATION("Animazioni", "Animation", "Fluidità dei movimenti e qualità delle scene animate.", "Smooth movement and the quality of animated scenes."),
    STORY("Trama", "Story", "Coerenza, ritmo e interesse della storia.", "The story's consistency, pacing and appeal."),
    PLOT_TWISTS("Plot twists", "Plot twists", "Colpi di scena sorprendenti e ben preparati.", "Surprising twists that feel well earned."),
    HIGHLIGHTS("Momenti salienti", "Highlights", "Scene memorabili che rendono speciale l'anime.", "Memorable scenes that make the anime stand out."),
    EMOTION("Impatto emotivo", "Emotional impact", "Quanto la storia ti ha coinvolto e fatto emozionare.", "How deeply the story moved and involved you."),
    PROTAGONISTS("Protagonisti", "Protagonists", "Personalità, crescita e motivazioni dei protagonisti.", "The main characters' personality, growth and motivations."),
    SUPPORTING("Personaggi secondari", "Supporting characters", "Profondità e contributo dei personaggi di supporto.", "The depth and contribution of supporting characters."),
    MUSIC("Intro / outro", "Opening / ending", "Musica e immagini delle sigle di apertura e chiusura.", "Music and visuals in the opening and ending sequences."),
    DIRECTION("Regia", "Direction", "Scelte di inquadratura, montaggio e messa in scena.", "Framing, editing and how scenes are presented.");

    public final String it, en, helpIt, helpEn;
    RatingCategory(String it, String en, String helpIt, String helpEn) {
        this.it = it; this.en = en; this.helpIt = helpIt; this.helpEn = helpEn;
    }

    public static void validate(Map<String, Integer> scores) {
        if (scores == null || scores.isEmpty()) return;
        if (scores.size() != values().length) throw new IllegalArgumentException("Incomplete rating");
        int bonuses = 0;
        for (RatingCategory category : values()) {
            Integer score = scores.get(category.name());
            if (score == null || score < 0 || (score > 10 && score != 12))
                throw new IllegalArgumentException("Invalid rating: " + category.name());
            if (score == 12) bonuses++;
        }
        if (bonuses > 1) throw new IllegalArgumentException("Only one score of 12 per anime");
    }
}
