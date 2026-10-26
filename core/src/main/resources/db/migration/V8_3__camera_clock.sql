-- US-25 Cấu hình mạng và thời gian: độ lệch đồng hồ camera so với máy chủ (đánh dấu khi lệch > 5 giây).

alter table camera
    add column clock_drift_seconds double precision,
    add column clock_checked_at    timestamptz;
