-- ============================================================
--  LOVE CCTV – Complete Supabase Database Schema
--  Run this entire file in the Supabase SQL Editor once.
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- 1. CAMERA LIVE STREAMS
--    One row per camera username.  Updated every ~1.2 s by
--    the camera device and read by the viewer device.
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.cctv_streams (
    id               BIGSERIAL PRIMARY KEY,
    username         TEXT        NOT NULL UNIQUE,   -- camera owner username
    is_live          BOOLEAN     NOT NULL DEFAULT TRUE,
    torch_on         BOOLEAN     NOT NULL DEFAULT FALSE,
    lens_facing_front BOOLEAN    NOT NULL DEFAULT FALSE,
    siren_active     BOOLEAN     NOT NULL DEFAULT FALSE,
    night_vision     BOOLEAN     NOT NULL DEFAULT FALSE,
    battery_level    INT         NOT NULL DEFAULT 100,
    recording_active BOOLEAN     NOT NULL DEFAULT FALSE,  -- remote recording control
    frame_base64     TEXT,                                -- optional JPEG frame (base64)
    updated_at       BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

-- Index for fast lookup by username
CREATE INDEX IF NOT EXISTS idx_cctv_streams_username ON public.cctv_streams (username);

-- Allow all authenticated + anonymous reads/writes (adjust with RLS below)
ALTER TABLE public.cctv_streams ENABLE ROW LEVEL SECURITY;

CREATE POLICY "allow_all_streams" ON public.cctv_streams
    FOR ALL USING (TRUE) WITH CHECK (TRUE);

-- ────────────────────────────────────────────────────────────
-- 2. MOTION ALERT LOG
--    Inserted by the camera device each time motion fires.
--    Read by the viewer device to show alerts and history.
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.cctv_motion_alerts (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username         TEXT        NOT NULL,   -- camera owner
    device_id        TEXT        NOT NULL,
    device_name      TEXT        NOT NULL DEFAULT 'Camera Device',
    severity         TEXT        NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    intensity        FLOAT       NOT NULL DEFAULT 0.0,  -- 0.0 – 1.0
    description      TEXT        NOT NULL DEFAULT '',
    snapshot_base64  TEXT,                              -- JPEG frame (base64)
    torch_triggered  BOOLEAN     NOT NULL DEFAULT FALSE, -- was flash auto-fired?
    acknowledged     BOOLEAN     NOT NULL DEFAULT FALSE, -- viewer dismissed it?
    timestamp        BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_motion_alerts_username   ON public.cctv_motion_alerts (username);
CREATE INDEX IF NOT EXISTS idx_motion_alerts_timestamp  ON public.cctv_motion_alerts (timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_motion_alerts_severity   ON public.cctv_motion_alerts (severity);
CREATE INDEX IF NOT EXISTS idx_motion_alerts_acked      ON public.cctv_motion_alerts (acknowledged, username);

ALTER TABLE public.cctv_motion_alerts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "allow_all_motion_alerts" ON public.cctv_motion_alerts
    FOR ALL USING (TRUE) WITH CHECK (TRUE);

-- ────────────────────────────────────────────────────────────
-- 3. FCM DEVICE TOKENS
--    Viewer phones register their FCM token here so the
--    camera device can address push notifications to them.
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.cctv_fcm_tokens (
    id               BIGSERIAL   PRIMARY KEY,
    username         TEXT        NOT NULL,       -- account owner
    device_label     TEXT        NOT NULL DEFAULT 'My Phone',
    fcm_token        TEXT        NOT NULL UNIQUE,
    is_viewer        BOOLEAN     NOT NULL DEFAULT TRUE,  -- TRUE = viewer, FALSE = camera
    last_seen        BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fcm_tokens_username ON public.cctv_fcm_tokens (username);

ALTER TABLE public.cctv_fcm_tokens ENABLE ROW LEVEL SECURITY;

CREATE POLICY "allow_all_fcm_tokens" ON public.cctv_fcm_tokens
    FOR ALL USING (TRUE) WITH CHECK (TRUE);

-- ────────────────────────────────────────────────────────────
-- 4. REMOTE COMMAND QUEUE
--    Viewer posts commands here; camera polls and consumes.
--    Commands: toggle_torch | toggle_siren | toggle_lens |
--              toggle_night_vision | start_recording | stop_recording
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.cctv_commands (
    id               BIGSERIAL   PRIMARY KEY,
    device_id        TEXT        NOT NULL,   -- target camera device id
    username         TEXT        NOT NULL,   -- account owner
    action           TEXT        NOT NULL,
    consumed         BOOLEAN     NOT NULL DEFAULT FALSE,
    timestamp        BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

CREATE INDEX IF NOT EXISTS idx_commands_device   ON public.cctv_commands (device_id, consumed);
CREATE INDEX IF NOT EXISTS idx_commands_username ON public.cctv_commands (username);

ALTER TABLE public.cctv_commands ENABLE ROW LEVEL SECURITY;

CREATE POLICY "allow_all_commands" ON public.cctv_commands
    FOR ALL USING (TRUE) WITH CHECK (TRUE);

-- ────────────────────────────────────────────────────────────
-- 5. VIEWER ALERT INBOX
--    Camera device writes here after motion fires, so viewer
--    can poll for new unread alerts efficiently.
--    Separate from cctv_motion_alerts (which is the full log).
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.cctv_viewer_inbox (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username         TEXT        NOT NULL,   -- account owner (shared between devices)
    alert_id         UUID        NOT NULL REFERENCES public.cctv_motion_alerts(id) ON DELETE CASCADE,
    read             BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_inbox_username_unread ON public.cctv_viewer_inbox (username, read);

ALTER TABLE public.cctv_viewer_inbox ENABLE ROW LEVEL SECURITY;

CREATE POLICY "allow_all_inbox" ON public.cctv_viewer_inbox
    FOR ALL USING (TRUE) WITH CHECK (TRUE);

-- ────────────────────────────────────────────────────────────
-- 6. PAIRED DEVICES
--    Stores pairing relationships between camera and viewer
--    devices under the same user account.
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.cctv_paired_devices (
    id               BIGSERIAL   PRIMARY KEY,
    username         TEXT        NOT NULL,
    pairing_code     TEXT        NOT NULL,
    device_name      TEXT        NOT NULL DEFAULT 'Paired Camera',
    ip_address       TEXT        NOT NULL DEFAULT '',
    created_at       BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    UNIQUE(username, pairing_code)
);

CREATE INDEX IF NOT EXISTS idx_paired_devices_username ON public.cctv_paired_devices (username);
CREATE INDEX IF NOT EXISTS idx_paired_devices_code ON public.cctv_paired_devices (pairing_code);

ALTER TABLE public.cctv_paired_devices ENABLE ROW LEVEL SECURITY;

CREATE POLICY "allow_all_paired_devices" ON public.cctv_paired_devices
    FOR ALL USING (TRUE) WITH CHECK (TRUE);

-- ────────────────────────────────────────────────────────────
-- 7. HELPER FUNCTION – Auto-populate viewer inbox on motion
--    Trigger: after INSERT on cctv_motion_alerts →
--    insert a row into cctv_viewer_inbox automatically.
-- ────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.fn_create_viewer_inbox_entry()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO public.cctv_viewer_inbox (username, alert_id, read)
    VALUES (NEW.username, NEW.id, FALSE);
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_motion_to_inbox ON public.cctv_motion_alerts;
CREATE TRIGGER trg_motion_to_inbox
    AFTER INSERT ON public.cctv_motion_alerts
    FOR EACH ROW EXECUTE FUNCTION public.fn_create_viewer_inbox_entry();

-- ────────────────────────────────────────────────────────────
-- 7. HELPER FUNCTION – Auto-purge old commands
--    Keeps the cctv_commands table lean (>24h old consumed rows)
-- ────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.fn_purge_old_commands()
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM public.cctv_commands
    WHERE consumed = TRUE
      AND timestamp < (EXTRACT(EPOCH FROM NOW() - INTERVAL '24 hours') * 1000)::BIGINT;
END;
$$;

-- ────────────────────────────────────────────────────────────
-- 8. REALTIME – Enable realtime on key tables so viewers
--    receive push updates without polling
-- ────────────────────────────────────────────────────────────
-- Run these in the Supabase Dashboard → Database → Replication
-- OR enable via the Supabase Dashboard UI under Table Editor → Realtime
ALTER PUBLICATION supabase_realtime ADD TABLE public.cctv_streams;
ALTER PUBLICATION supabase_realtime ADD TABLE public.cctv_motion_alerts;
ALTER PUBLICATION supabase_realtime ADD TABLE public.cctv_viewer_inbox;

-- ────────────────────────────────────────────────────────────
-- 9. DEVICE PREFERENCES & REBOOT RECOVERY
--    Stores whether this phone is configured as Live Cam or
--    View Cam with auto-open on startup & reboot auto-start.
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.cctv_device_preferences (
    device_id          TEXT        PRIMARY KEY,
    username           TEXT        NOT NULL,
    selected_mode      TEXT        NOT NULL DEFAULT 'CAMERA_SENDER', -- 'CAMERA_SENDER' or 'VIEWER_MONITOR'
    remember_choice    BOOLEAN     NOT NULL DEFAULT TRUE,
    auto_start_on_boot BOOLEAN     NOT NULL DEFAULT TRUE,
    device_model       TEXT,
    updated_at         BIGINT      NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

CREATE INDEX IF NOT EXISTS idx_device_prefs_user ON public.cctv_device_preferences (username);

ALTER TABLE public.cctv_device_preferences ENABLE ROW LEVEL SECURITY;

CREATE POLICY "allow_all_device_preferences" ON public.cctv_device_preferences
    FOR ALL USING (TRUE) WITH CHECK (TRUE);

-- Realtime publication
ALTER PUBLICATION supabase_realtime ADD TABLE public.cctv_device_preferences;

-- ────────────────────────────────────────────────────────────
-- 10. SAMPLE SEED DATA (optional – remove before production)
-- ────────────────────────────────────────────────────────────
INSERT INTO public.cctv_streams (username, is_live, battery_level)
VALUES ('demo_user', FALSE, 100)
ON CONFLICT (username) DO NOTHING;

-- ────────────────────────────────────────────────────────────
-- DONE.
-- Tables created:
--   cctv_device_preferences – remembers phone role (Live Cam / View Cam)
--   cctv_streams            – live camera status (polling + realtime)
--   cctv_motion_alerts      – full motion incident log with snapshots
--   cctv_fcm_tokens         – FCM push notification tokens
--   cctv_commands           – viewer → camera remote command queue
--   cctv_viewer_inbox       – unread alert inbox for viewer phone
--   cctv_paired_devices     – paired cameras and viewers
-- ────────────────────────────────────────────────────────────
