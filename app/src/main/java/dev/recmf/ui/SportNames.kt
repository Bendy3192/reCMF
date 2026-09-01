/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.annotation.StringRes
import dev.recmf.R
import dev.recmf.protocol.CmfActivityType

/**
 * What to call each exercise, in the reader's own language.
 *
 * These used to be the enum's own names with the underscores taken out, which is fine
 * until the app is read in a language that is not the one the protocol was written in:
 * a Russian screen listing "Cross Country Skiing" is not a translated screen, it is an
 * English screen with Russian headings.
 *
 * A `when` over every case rather than a map or a name lookup at runtime. The compiler
 * then refuses to build if the watch's list ever grows and this does not, which is the
 * only way a table this long stays complete — and a missing entry here would show as a
 * crash rather than as an English word.
 */
@StringRes
fun CmfActivityType.labelRes(): Int = when (this) {
    CmfActivityType.INDOOR_RUNNING -> R.string.sport_indoor_running
    CmfActivityType.OUTDOOR_RUNNING -> R.string.sport_outdoor_running
    CmfActivityType.OUTDOOR_WALKING -> R.string.sport_outdoor_walking
    CmfActivityType.INDOOR_WALKING -> R.string.sport_indoor_walking
    CmfActivityType.OUTDOOR_CYCLING -> R.string.sport_outdoor_cycling
    CmfActivityType.INDOOR_CYCLING -> R.string.sport_indoor_cycling
    CmfActivityType.MOUNTAIN_HIKE -> R.string.sport_mountain_hike
    CmfActivityType.HIKING -> R.string.sport_hiking
    CmfActivityType.CROSS_TRAINER -> R.string.sport_cross_trainer
    CmfActivityType.FREE_TRAINING -> R.string.sport_free_training
    CmfActivityType.STRENGTH_TRAINING -> R.string.sport_strength_training
    CmfActivityType.YOGA -> R.string.sport_yoga
    CmfActivityType.BOXING -> R.string.sport_boxing
    CmfActivityType.ROWER -> R.string.sport_rower
    CmfActivityType.DYNAMIC_CYCLE -> R.string.sport_dynamic_cycle
    CmfActivityType.STAIR_STEPPER -> R.string.sport_stair_stepper
    CmfActivityType.TREADMILL -> R.string.sport_treadmill
    CmfActivityType.KICKBOXING -> R.string.sport_kickboxing
    CmfActivityType.HIIT -> R.string.sport_hiit
    CmfActivityType.FITNESS_EXERCISES -> R.string.sport_fitness_exercises
    CmfActivityType.JUMP_ROPING -> R.string.sport_jump_roping
    CmfActivityType.PILATES -> R.string.sport_pilates
    CmfActivityType.CROSSFIT -> R.string.sport_crossfit
    CmfActivityType.FUNCTIONAL_TRAINING -> R.string.sport_functional_training
    CmfActivityType.PHYSICAL_TRAINING -> R.string.sport_physical_training
    CmfActivityType.TAEKWONDO -> R.string.sport_taekwondo
    CmfActivityType.TAE_BO -> R.string.sport_tae_bo
    CmfActivityType.CROSS_COUNTRY_RUNNING -> R.string.sport_cross_country_running
    CmfActivityType.KARATE -> R.string.sport_karate
    CmfActivityType.FENCING -> R.string.sport_fencing
    CmfActivityType.CORE_TRAINING -> R.string.sport_core_training
    CmfActivityType.KENDO -> R.string.sport_kendo
    CmfActivityType.HORIZONTAL_BAR -> R.string.sport_horizontal_bar
    CmfActivityType.PARALLEL_BAR -> R.string.sport_parallel_bar
    CmfActivityType.COOLDOWN -> R.string.sport_cooldown
    CmfActivityType.CROSS_TRAINING -> R.string.sport_cross_training
    CmfActivityType.SIT_UPS -> R.string.sport_sit_ups
    CmfActivityType.FITNESS_GAMING -> R.string.sport_fitness_gaming
    CmfActivityType.AEROBIC_EXERCISE -> R.string.sport_aerobic_exercise
    CmfActivityType.ROLLING -> R.string.sport_rolling
    CmfActivityType.FLEXIBILITY -> R.string.sport_flexibility
    CmfActivityType.GYMNASTICS -> R.string.sport_gymnastics
    CmfActivityType.TRACK_AND_FIELD -> R.string.sport_track_and_field
    CmfActivityType.PUSH_UPS -> R.string.sport_push_ups
    CmfActivityType.BATTLE_ROPE -> R.string.sport_battle_rope
    CmfActivityType.SMITH_MACHINE -> R.string.sport_smith_machine
    CmfActivityType.PULL_UPS -> R.string.sport_pull_ups
    CmfActivityType.PLANK -> R.string.sport_plank
    CmfActivityType.JAVELIN -> R.string.sport_javelin
    CmfActivityType.LONG_JUMP -> R.string.sport_long_jump
    CmfActivityType.HIGH_JUMP -> R.string.sport_high_jump
    CmfActivityType.TRAMPOLINE -> R.string.sport_trampoline
    CmfActivityType.DUMBBELL -> R.string.sport_dumbbell
    CmfActivityType.BELLY_DANCE -> R.string.sport_belly_dance
    CmfActivityType.JAZZ_DANCE -> R.string.sport_jazz_dance
    CmfActivityType.LATIN_DANCE -> R.string.sport_latin_dance
    CmfActivityType.BALLET -> R.string.sport_ballet
    CmfActivityType.STREET_DANCE -> R.string.sport_street_dance
    CmfActivityType.ZUMBA -> R.string.sport_zumba
    CmfActivityType.OTHER_DANCE -> R.string.sport_other_dance
    CmfActivityType.ROLLER_SKATING -> R.string.sport_roller_skating
    CmfActivityType.MARTIAL_ARTS -> R.string.sport_martial_arts
    CmfActivityType.TAI_CHI -> R.string.sport_tai_chi
    CmfActivityType.HULA_HOOPING -> R.string.sport_hula_hooping
    CmfActivityType.DISC_SPORTS -> R.string.sport_disc_sports
    CmfActivityType.DARTS -> R.string.sport_darts
    CmfActivityType.ARCHERY -> R.string.sport_archery
    CmfActivityType.HORSE_RIDING -> R.string.sport_horse_riding
    CmfActivityType.KITE_FLYING -> R.string.sport_kite_flying
    CmfActivityType.SWING -> R.string.sport_swing
    CmfActivityType.STAIRS -> R.string.sport_stairs
    CmfActivityType.FISHING -> R.string.sport_fishing
    CmfActivityType.HAND_CYCLING -> R.string.sport_hand_cycling
    CmfActivityType.MIND_AND_BODY -> R.string.sport_mind_and_body
    CmfActivityType.WRESTLING -> R.string.sport_wrestling
    CmfActivityType.KABADDI -> R.string.sport_kabaddi
    CmfActivityType.KARTING -> R.string.sport_karting
    CmfActivityType.BADMINTON -> R.string.sport_badminton
    CmfActivityType.TABLE_TENNIS -> R.string.sport_table_tennis
    CmfActivityType.TENNIS -> R.string.sport_tennis
    CmfActivityType.BILLIARDS -> R.string.sport_billiards
    CmfActivityType.BOWLING -> R.string.sport_bowling
    CmfActivityType.VOLLEYBALL -> R.string.sport_volleyball
    CmfActivityType.SHUTTLECOCK -> R.string.sport_shuttlecock
    CmfActivityType.HANDBALL -> R.string.sport_handball
    CmfActivityType.BASEBALL -> R.string.sport_baseball
    CmfActivityType.SOFTBALL -> R.string.sport_softball
    CmfActivityType.CRICKET -> R.string.sport_cricket
    CmfActivityType.RUGBY -> R.string.sport_rugby
    CmfActivityType.HOCKEY -> R.string.sport_hockey
    CmfActivityType.SQUASH -> R.string.sport_squash
    CmfActivityType.DODGEBALL -> R.string.sport_dodgeball
    CmfActivityType.SOCCER -> R.string.sport_soccer
    CmfActivityType.BASKETBALL -> R.string.sport_basketball
    CmfActivityType.AUSTRALIAN_FOOTBALL -> R.string.sport_australian_football
    CmfActivityType.GOLF -> R.string.sport_golf
    CmfActivityType.PICKLEBALL -> R.string.sport_pickleball
    CmfActivityType.LACROSS -> R.string.sport_lacross
    CmfActivityType.SHOT -> R.string.sport_shot
    CmfActivityType.BEACH_SOCCER -> R.string.sport_beach_soccer
    CmfActivityType.BEACH_VOLLEYBALL -> R.string.sport_beach_volleyball
    CmfActivityType.GATEBALL -> R.string.sport_gateball
    CmfActivityType.SEPAK_TAKRAW -> R.string.sport_sepak_takraw
    CmfActivityType.SAILING -> R.string.sport_sailing
    CmfActivityType.SURFING -> R.string.sport_surfing
    CmfActivityType.JET_SKIING -> R.string.sport_jet_skiing
    CmfActivityType.SKATING -> R.string.sport_skating
    CmfActivityType.ICE_HOCKEY -> R.string.sport_ice_hockey
    CmfActivityType.CURLING -> R.string.sport_curling
    CmfActivityType.SNOWBOARDING -> R.string.sport_snowboarding
    CmfActivityType.CROSS_COUNTRY_SKIING -> R.string.sport_cross_country_skiing
    CmfActivityType.SNOW_SPORTS -> R.string.sport_snow_sports
    CmfActivityType.SKIING -> R.string.sport_skiing
    CmfActivityType.LUGE -> R.string.sport_luge
    CmfActivityType.SKATEBOARDING -> R.string.sport_skateboarding
    CmfActivityType.ROCK_CLIMBING -> R.string.sport_rock_climbing
    CmfActivityType.HUNTING -> R.string.sport_hunting
    CmfActivityType.PARACHUTING -> R.string.sport_parachuting
    CmfActivityType.AUTO_RACING -> R.string.sport_auto_racing
    CmfActivityType.PARKOUR -> R.string.sport_parkour
}
