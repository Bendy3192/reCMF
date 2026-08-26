# Room generates implementations that are only referenced reflectively at build time;
# R8 keeps them via the generated code, so no extra rules are needed there.

# Health Connect's client talks to another process over a generated AIDL surface.
-keep class androidx.health.platform.client.** { *; }
