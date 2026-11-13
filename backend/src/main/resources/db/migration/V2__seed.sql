INSERT INTO departments(name) VALUES ('ICU'),('Emergency'),('Radiology'),('Surgery');

INSERT INTO assets(asset_tag,name,category,department_id,maintenance_interval_days,maintenance_threshold_hours,last_maintenance_at,next_maintenance_due)
SELECT v.tag, v.name, v.cat, d.id, v.days, v.hrs, now() - interval '30 days', now() + (v.days - 30) * interval '1 day'
FROM (VALUES
  ('VENT-0001','Ventilator A','Ventilator','ICU',90,500),
  ('VENT-0002','Ventilator B','Ventilator','Emergency',90,500),
  ('INFP-0001','Infusion Pump A','Infusion Pump','ICU',60,300),
  ('XRAY-0001','Portable X-Ray','Imaging','Radiology',120,800),
  ('DEFB-0001','Defibrillator A','Defibrillator','Emergency',45,200),
  ('USND-0001','Ultrasound A','Imaging','Surgery',90,600)
) AS v(tag,name,cat,dept,days,hrs)
JOIN departments d ON d.name = v.dept;

INSERT INTO asset_aliases(alias, asset_id)
SELECT 'OLDVENTA', id FROM assets WHERE asset_tag='VENT-0001';
