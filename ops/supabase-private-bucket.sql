-- Supabase deployment only. SQL bucket creation is documented by Supabase.
-- No object metadata is ever inserted/deleted directly; use the Storage API.
INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
VALUES ('reportes-evidencias','reportes-evidencias',false,5242880,ARRAY['image/jpeg','image/png'])
ON CONFLICT(id) DO NOTHING;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM storage.buckets WHERE id='reportes-evidencias'
        AND (public OR file_size_limit IS DISTINCT FROM 5242880::bigint
            OR allowed_mime_types IS DISTINCT FROM ARRAY['image/jpeg','image/png']::text[])) THEN
        RAISE EXCEPTION 'Existing evidence bucket has unexpected settings; review it before deployment';
    END IF;
END $$;
-- No anon/authenticated Storage policies: access exclusively through authorized backend.
