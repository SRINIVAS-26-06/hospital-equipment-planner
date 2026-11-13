-- Data as the previous release (schema V1-V3) would have left it. Used to prove V4+ preserve it.
UPDATE assets SET department_id = (SELECT id FROM departments WHERE name = 'Surgery') WHERE asset_tag = 'VENT-0002';
INSERT INTO transfers(asset_id, from_dept_id, to_dept_id, transferred_at)
SELECT a.id, (SELECT id FROM departments WHERE name='Emergency'), (SELECT id FROM departments WHERE name='ICU'), now() - interval '10 days'
FROM assets a WHERE a.asset_tag = 'VENT-0002';
INSERT INTO transfers(asset_id, from_dept_id, to_dept_id, transferred_at)
SELECT a.id, (SELECT id FROM departments WHERE name='ICU'), (SELECT id FROM departments WHERE name='Surgery'), now() - interval '3 days'
FROM assets a WHERE a.asset_tag = 'VENT-0002';

UPDATE assets SET status = 'OUT_OF_SERVICE' WHERE asset_tag = 'DEFB-0001';
UPDATE assets SET status = 'IN_USE' WHERE asset_tag = 'INFP-0001';

INSERT INTO bookings(asset_id, department_id, requested_by, start_at, end_at, status)
SELECT id, department_id, 'staff', now() + interval '1 day', now() + interval '1 day 2 hours', 'ACTIVE' FROM assets WHERE asset_tag = 'XRAY-0001';
INSERT INTO bookings(asset_id, department_id, requested_by, start_at, end_at, status)
SELECT id, department_id, 'staff', now() + interval '2 day', now() + interval '2 day 1 hour', 'CANCELLED' FROM assets WHERE asset_tag = 'XRAY-0001';

INSERT INTO maintenance_windows(asset_id, kind, start_at, end_at, status)
SELECT id, 'PREVENTIVE', now() - interval '20 days', now() - interval '20 days' + interval '4 hours', 'COMPLETED' FROM assets WHERE asset_tag = 'USND-0001';
INSERT INTO maintenance_windows(asset_id, kind, start_at, end_at, status)
SELECT id, 'PREVENTIVE', now() + interval '5 days', now() + interval '5 days 3 hours', 'SCHEDULED' FROM assets WHERE asset_tag = 'USND-0001';

INSERT INTO usage_events(asset_id, start_at, end_at, source)
SELECT id, now() - interval '2 days', now() - interval '2 days' + interval '3 hours', 'bedside' FROM assets WHERE asset_tag = 'VENT-0002';
INSERT INTO usage_events(asset_id, start_at, end_at, source)
SELECT id, now() - interval '12 days', now() - interval '12 days' + interval '5 hours', 'bedside' FROM assets WHERE asset_tag = 'VENT-0002';
INSERT INTO downtime_events(asset_id, start_at, end_at, reason)
SELECT id, now() - interval '8 days', now() - interval '8 days' + interval '2 hours', 'POWER_FAILURE' FROM assets WHERE asset_tag = 'XRAY-0001';
