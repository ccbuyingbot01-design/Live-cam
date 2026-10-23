-- ====================================================================
-- LOVE CCTV – Supabase Cleanup & Deduplication Script
-- Run this in your Supabase SQL Editor to purge duplicate paired devices
-- ====================================================================

-- 1. Remove duplicate paired devices, keeping only the latest entry per (username, pairing_code)
DELETE FROM public.cctv_paired_devices a
USING public.cctv_paired_devices b
WHERE a.id < b.id
  AND a.username = b.username
  AND a.pairing_code = b.pairing_code;

-- 2. Drop existing constraint if present and enforce UNIQUE constraint on (username, pairing_code)
ALTER TABLE public.cctv_paired_devices 
DROP CONSTRAINT IF EXISTS cctv_paired_devices_username_pairing_code_key;

ALTER TABLE public.cctv_paired_devices 
ADD CONSTRAINT cctv_paired_devices_username_pairing_code_key UNIQUE (username, pairing_code);
