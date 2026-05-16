#!/bin/bash

# =============================================================
# 1. Existing testuser schema (simple for tests in the application)
# =============================================================
sqlplus -s testuser/testuser123@//localhost:1521/XEPDB1 <<'EOSQL'
SET SERVEROUTPUT ON

DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'DEPARTMENTS';
  IF v_count > 0 THEN
    DBMS_OUTPUT.PUT_LINE('testuser: Tables already exist — skipping init.');
    RETURN;
  END IF;

  EXECUTE IMMEDIATE 'CREATE TABLE departments (
    dept_id   NUMBER PRIMARY KEY,
    name      VARCHAR2(100) NOT NULL,
    location  VARCHAR2(100))';

  EXECUTE IMMEDIATE 'CREATE TABLE employees (
    emp_id      NUMBER PRIMARY KEY,
    first_name  VARCHAR2(50),
    last_name   VARCHAR2(50) NOT NULL,
    email       VARCHAR2(100),
    hire_date   DATE DEFAULT SYSDATE,
    salary      NUMBER(10,2),
    dept_id     NUMBER REFERENCES departments(dept_id))';

  EXECUTE IMMEDIATE 'CREATE TABLE projects (
    project_id   NUMBER PRIMARY KEY,
    name         VARCHAR2(200) NOT NULL,
    start_date   DATE,
    end_date     DATE,
    budget       NUMBER(12,2))';

  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (1, ''Engineering'', ''Building A'')';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (2, ''Marketing'', ''Building B'')';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (3, ''Finance'', ''Building C'')';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (4, ''HR'', ''Building A'')';

  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (1, ''Alice'', ''Smith'', ''alice@example.com'', DATE ''2020-03-15'', 95000, 1)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (2, ''Bob'', ''Jones'', ''bob@example.com'', DATE ''2019-07-01'', 88000, 1)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (3, ''Carol'', ''White'', ''carol@example.com'', DATE ''2021-01-10'', 72000, 2)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (4, ''Dave'', ''Brown'', ''dave@example.com'', DATE ''2018-11-20'', 105000, 3)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (5, ''Eve'', ''Davis'', ''eve@example.com'', DATE ''2022-06-05'', 68000, 4)';

  EXECUTE IMMEDIATE 'INSERT INTO projects VALUES (1, ''Website Redesign'', DATE ''2024-01-01'', DATE ''2024-06-30'', 150000)';
  EXECUTE IMMEDIATE 'INSERT INTO projects VALUES (2, ''Data Migration'', DATE ''2024-03-01'', NULL, 80000)';
  EXECUTE IMMEDIATE 'INSERT INTO projects VALUES (3, ''Mobile App'', DATE ''2024-02-15'', DATE ''2024-12-31'', 200000)';

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('testuser: Init complete — 3 tables, 12 rows.');
END;
/

EXIT;
EOSQL

# =============================================================
# 2. Create demo users (connect as system)
# =============================================================
sqlplus -s system/SecretPassword123@//localhost:1521/XEPDB1 <<'EOSQL'
SET SERVEROUTPUT ON

DECLARE
  v_count NUMBER;
BEGIN
  -- demohr user
  SELECT COUNT(*) INTO v_count FROM all_users WHERE username = 'DEMOHR';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE USER demohr IDENTIFIED BY demohr123 DEFAULT TABLESPACE users TEMPORARY TABLESPACE temp QUOTA UNLIMITED ON users';
    EXECUTE IMMEDIATE 'GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE SEQUENCE, CREATE TRIGGER, CREATE SYNONYM TO demohr';
    DBMS_OUTPUT.PUT_LINE('Created user demohr');
  ELSE
    DBMS_OUTPUT.PUT_LINE('User demohr already exists');
  END IF;

  -- demolegacy user
  SELECT COUNT(*) INTO v_count FROM all_users WHERE username = 'DEMOLEGACY';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE USER demolegacy IDENTIFIED BY demolegacy123 DEFAULT TABLESPACE users TEMPORARY TABLESPACE temp QUOTA UNLIMITED ON users';
    EXECUTE IMMEDIATE 'GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE SEQUENCE, CREATE TRIGGER, CREATE SYNONYM, CREATE MATERIALIZED VIEW, CREATE TYPE TO demolegacy';
    DBMS_OUTPUT.PUT_LINE('Created user demolegacy');
  ELSE
    DBMS_OUTPUT.PUT_LINE('User demolegacy already exists');
  END IF;

  -- demominor user
    SELECT COUNT(*) INTO v_count FROM all_users WHERE username = 'DEMOMINOR';
    IF v_count = 0 THEN
      EXECUTE IMMEDIATE 'CREATE USER demominor IDENTIFIED BY demominor123 DEFAULT TABLESPACE users TEMPORARY TABLESPACE temp QUOTA UNLIMITED ON users';
      EXECUTE IMMEDIATE 'GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE SEQUENCE, CREATE TRIGGER, CREATE SYNONYM, CREATE MATERIALIZED VIEW, CREATE TYPE TO demominor';
      DBMS_OUTPUT.PUT_LINE('Created user demominor');
    ELSE
      DBMS_OUTPUT.PUT_LINE('User demominor already exists');
    END IF;

  COMMIT;
END;
/

EXIT;
EOSQL

# =============================================================
# 3. demohr schema — Rich HR database (clean migration)
# =============================================================
sqlplus -s demohr/demohr123@//localhost:1521/XEPDB1 <<'EOSQL'
SET SERVEROUTPUT ON

DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'REGIONS';
  IF v_count > 0 THEN
    DBMS_OUTPUT.PUT_LINE('demohr: Tables already exist — skipping init.');
    RETURN;
  END IF;

  -- Sequences
  EXECUTE IMMEDIATE 'CREATE SEQUENCE employees_seq START WITH 100 INCREMENT BY 1';
  EXECUTE IMMEDIATE 'CREATE SEQUENCE departments_seq START WITH 300 INCREMENT BY 10';
  EXECUTE IMMEDIATE 'CREATE SEQUENCE locations_seq START WITH 4000 INCREMENT BY 100';
  EXECUTE IMMEDIATE 'CREATE SEQUENCE audit_seq START WITH 1 INCREMENT BY 1';

  -- Tables
  EXECUTE IMMEDIATE 'CREATE TABLE regions (
    region_id   NUMBER PRIMARY KEY,
    region_name VARCHAR2(50) NOT NULL)';

  EXECUTE IMMEDIATE 'CREATE TABLE countries (
    country_code CHAR(2) PRIMARY KEY,
    country_name VARCHAR2(100) NOT NULL,
    region_id    NUMBER REFERENCES regions(region_id))';

  EXECUTE IMMEDIATE 'CREATE TABLE locations (
    location_id    NUMBER PRIMARY KEY,
    street_address VARCHAR2(200),
    postal_code    VARCHAR2(20),
    city           VARCHAR2(100) NOT NULL,
    state_province VARCHAR2(50),
    country_code   CHAR(2) REFERENCES countries(country_code))';

  EXECUTE IMMEDIATE 'CREATE TABLE jobs (
    job_id     VARCHAR2(10) PRIMARY KEY,
    job_title  VARCHAR2(100) NOT NULL,
    min_salary NUMBER(10,2),
    max_salary NUMBER(10,2),
    CONSTRAINT job_salary_check CHECK (max_salary >= min_salary))';

  EXECUTE IMMEDIATE 'CREATE TABLE departments (
    department_id   NUMBER PRIMARY KEY,
    department_name VARCHAR2(100) NOT NULL,
    manager_id      NUMBER,
    location_id     NUMBER REFERENCES locations(location_id))';

  EXECUTE IMMEDIATE 'CREATE TABLE employees (
    employee_id    NUMBER PRIMARY KEY,
    first_name     VARCHAR2(50),
    last_name      VARCHAR2(50) NOT NULL,
    email          VARCHAR2(100) NOT NULL UNIQUE,
    phone_number   VARCHAR2(20),
    hire_date      DATE NOT NULL,
    job_id         VARCHAR2(10) NOT NULL REFERENCES jobs(job_id),
    salary         NUMBER(10,2) CONSTRAINT emp_salary_positive CHECK (salary > 0),
    commission_pct NUMBER(3,2),
    manager_id     NUMBER,
    department_id  NUMBER REFERENCES departments(department_id))';

  EXECUTE IMMEDIATE 'ALTER TABLE departments ADD CONSTRAINT dept_mgr_fk FOREIGN KEY (manager_id) REFERENCES employees(employee_id)';
  EXECUTE IMMEDIATE 'ALTER TABLE employees ADD CONSTRAINT emp_mgr_fk FOREIGN KEY (manager_id) REFERENCES employees(employee_id)';

  EXECUTE IMMEDIATE 'CREATE TABLE job_history (
    employee_id  NUMBER NOT NULL REFERENCES employees(employee_id),
    start_date   DATE NOT NULL,
    end_date     DATE NOT NULL,
    job_id       VARCHAR2(10) NOT NULL REFERENCES jobs(job_id),
    department_id NUMBER REFERENCES departments(department_id),
    CONSTRAINT job_history_pk PRIMARY KEY (employee_id, start_date),
    CONSTRAINT job_hist_dates CHECK (end_date > start_date))';

  EXECUTE IMMEDIATE 'CREATE TABLE audit_log (
    audit_id     NUMBER PRIMARY KEY,
    table_name   VARCHAR2(50),
    operation    VARCHAR2(10),
    changed_by   VARCHAR2(100),
    changed_at   DATE DEFAULT SYSDATE,
    old_value    CLOB,
    new_value    CLOB)';

  -- Indexes
  EXECUTE IMMEDIATE 'CREATE INDEX emp_name_idx ON employees(last_name, first_name)';
  EXECUTE IMMEDIATE 'CREATE INDEX emp_dept_idx ON employees(department_id)';
  EXECUTE IMMEDIATE 'CREATE INDEX emp_job_idx ON employees(job_id)';
  EXECUTE IMMEDIATE 'CREATE INDEX jhist_emp_idx ON job_history(employee_id)';

  -- View
  EXECUTE IMMEDIATE 'CREATE VIEW emp_details_view AS
    SELECT e.employee_id, e.first_name, e.last_name, e.email, e.phone_number,
           e.hire_date, e.salary, e.commission_pct,
           j.job_id, j.job_title,
           d.department_id, d.department_name,
           l.city, l.state_province, c.country_name, r.region_name
    FROM employees e
    JOIN jobs j ON e.job_id = j.job_id
    LEFT JOIN departments d ON e.department_id = d.department_id
    LEFT JOIN locations l ON d.location_id = l.location_id
    LEFT JOIN countries c ON l.country_code = c.country_code
    LEFT JOIN regions r ON c.region_id = r.region_id';

  -- Trigger
  EXECUTE IMMEDIATE 'CREATE OR REPLACE TRIGGER emp_audit_trigger
    AFTER UPDATE ON employees
    FOR EACH ROW
  BEGIN
    INSERT INTO audit_log (audit_id, table_name, operation, changed_by, changed_at, old_value, new_value)
    VALUES (audit_seq.NEXTVAL, ''EMPLOYEES'', ''UPDATE'', USER, SYSDATE,
            ''salary='' || :OLD.salary || '',job='' || :OLD.job_id,
            ''salary='' || :NEW.salary || '',job='' || :NEW.job_id);
  END;';

  -- ===================== DATA =====================

  -- Regions
  EXECUTE IMMEDIATE 'INSERT INTO regions VALUES (1, ''Europe'')';
  EXECUTE IMMEDIATE 'INSERT INTO regions VALUES (2, ''Americas'')';
  EXECUTE IMMEDIATE 'INSERT INTO regions VALUES (3, ''Asia'')';
  EXECUTE IMMEDIATE 'INSERT INTO regions VALUES (4, ''Africa'')';
  EXECUTE IMMEDIATE 'INSERT INTO regions VALUES (5, ''Oceania'')';

  -- Countries
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''US'', ''United States'', 2)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''CA'', ''Canada'', 2)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''GB'', ''United Kingdom'', 1)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''DE'', ''Germany'', 1)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''FR'', ''France'', 1)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''JP'', ''Japan'', 3)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''AU'', ''Australia'', 5)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''BR'', ''Brazil'', 2)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''IN'', ''India'', 3)';
  EXECUTE IMMEDIATE 'INSERT INTO countries VALUES (''ZA'', ''South Africa'', 4)';

  -- Locations
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1000, ''123 Main St'', ''10001'', ''New York'', ''NY'', ''US'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1100, ''456 Bay St'', ''M5H 2N2'', ''Toronto'', ''ON'', ''CA'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1200, ''10 Downing St'', ''SW1A 2AA'', ''London'', NULL, ''GB'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1300, ''Friedrichstr 100'', ''10117'', ''Berlin'', NULL, ''DE'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1400, ''8 Rue de Rivoli'', ''75001'', ''Paris'', NULL, ''FR'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1500, ''1-1 Marunouchi'', ''100-0005'', ''Tokyo'', NULL, ''JP'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1600, ''200 George St'', ''2000'', ''Sydney'', ''NSW'', ''AU'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1700, ''Av Paulista 1000'', ''01310-100'', ''Sao Paulo'', ''SP'', ''BR'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1800, ''MG Road 45'', ''560001'', ''Bangalore'', ''KA'', ''IN'')';
  EXECUTE IMMEDIATE 'INSERT INTO locations VALUES (1900, ''100 Sandton Dr'', ''2196'', ''Johannesburg'', ''GP'', ''ZA'')';

  -- Jobs
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''CEO'',      ''Chief Executive Officer'',    200000, 400000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''CTO'',      ''Chief Technology Officer'',   180000, 350000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''CFO'',      ''Chief Financial Officer'',    180000, 350000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''VP_ENG'',   ''VP Engineering'',             150000, 280000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''VP_SALES'', ''VP Sales'',                   140000, 260000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''MGR'',      ''Manager'',                     90000, 160000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''SR_DEV'',   ''Senior Developer'',            95000, 170000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''DEV'',      ''Software Developer'',          65000, 120000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''QA'',       ''QA Engineer'',                 55000, 105000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''ANALYST'',  ''Business Analyst'',            60000, 115000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''HR_REP'',   ''HR Representative'',           45000,  85000)';
  EXECUTE IMMEDIATE 'INSERT INTO jobs VALUES (''ACCT'',     ''Accountant'',                  50000,  95000)';

  -- Departments (manager_id set to NULL initially, updated after employees)
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (10, ''Executive'',     NULL, 1000)';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (20, ''Engineering'',   NULL, 1000)';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (30, ''Sales'',         NULL, 1200)';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (40, ''Finance'',       NULL, 1300)';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (50, ''HR'',            NULL, 1400)';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (60, ''QA'',            NULL, 1500)';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (70, ''Research'',      NULL, 1600)';
  EXECUTE IMMEDIATE 'INSERT INTO departments VALUES (80, ''Operations'',    NULL, 1800)';

  -- Employees (50 rows)
  -- Executives
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (1,  ''Steven'',   ''King'',      ''sking'',       ''515-123-4567'', DATE ''2003-06-17'', ''CEO'',     300000, NULL,  NULL, 10)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (2,  ''Neena'',    ''Kochhar'',   ''nkochhar'',    ''515-123-4568'', DATE ''2005-09-21'', ''CTO'',     250000, NULL,  1,    10)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (3,  ''Lex'',      ''De Haan'',   ''ldehaan'',     ''515-123-4569'', DATE ''2001-01-13'', ''CFO'',     240000, NULL,  1,    10)';

  -- Engineering managers + staff
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (10, ''Alexander'', ''Hunold'',   ''ahunold'',     ''590-423-4567'', DATE ''2006-01-03'', ''VP_ENG'',  190000, NULL,  2,    20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (11, ''Bruce'',    ''Ernst'',     ''bernst'',      ''590-423-4568'', DATE ''2007-05-21'', ''SR_DEV'',  140000, NULL,  10,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (12, ''David'',    ''Austin'',    ''daustin'',     ''590-423-4569'', DATE ''2005-06-25'', ''SR_DEV'',  135000, NULL,  10,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (13, ''Valli'',    ''Pataballa'', ''vpatabal'',    ''590-423-4560'', DATE ''2006-02-05'', ''DEV'',     105000, NULL,  10,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (14, ''Diana'',    ''Lorentz'',   ''dlorentz'',    ''590-423-5567'', DATE ''2007-02-07'', ''DEV'',      98000, NULL,  10,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (15, ''Kevin'',    ''Mourgos'',   ''kmourgos'',    ''650-123-5234'', DATE ''2007-11-16'', ''DEV'',      92000, NULL,  10,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (16, ''Amit'',     ''Banda'',     ''abanda'',      ''650-124-1234'', DATE ''2008-04-21'', ''DEV'',      85000, NULL,  11,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (17, ''Lisa'',     ''Ozer'',      ''lozer'',       ''650-124-5234'', DATE ''2005-03-11'', ''DEV'',      88000, NULL,  11,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (18, ''Harrison'', ''Bloom'',     ''hbloom'',      ''650-125-1234'', DATE ''2006-03-23'', ''DEV'',      82000, NULL,  11,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (19, ''James'',    ''Marlow'',    ''jmarlow'',     ''650-125-5234'', DATE ''2005-02-16'', ''DEV'',      79000, NULL,  12,   20)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (20, ''TJ'',       ''Olson'',     ''tjolson'',     ''650-126-1234'', DATE ''2007-04-10'', ''DEV'',      75000, NULL,  12,   20)';

  -- Sales
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (21, ''Clara'',    ''Vishney'',   ''cvishney'',    ''011-44-1346'',  DATE ''2005-11-11'', ''VP_SALES'',175000, NULL,  1,    30)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (22, ''Danielle'', ''Greene'',    ''dgreene'',     ''011-44-1347'',  DATE ''2007-03-19'', ''MGR'',     120000, 0.15,  21,   30)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (23, ''Mattea'',   ''Marvins'',   ''mmarvins'',    ''011-44-1348'',  DATE ''2008-01-24'', ''MGR'',     115000, 0.20,  21,   30)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (24, ''David'',    ''Lee'',       ''dlee'',        ''011-44-1349'',  DATE ''2008-02-23'', ''ANALYST'', 100000, 0.10,  22,   30)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (25, ''Sundar'',   ''Ande'',      ''sande'',       ''011-44-1350'',  DATE ''2008-03-24'', ''ANALYST'',  95000, 0.10,  22,   30)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (26, ''Amit'',     ''Errazuriz'', ''aerrazur'',    ''011-44-1351'',  DATE ''2005-12-10'', ''ANALYST'',  92000, 0.15,  23,   30)';

  -- Finance
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (30, ''Nancy'',    ''Greenberg'', ''ngreen'',      ''515-124-4569'', DATE ''2002-08-17'', ''MGR'',     130000, NULL,  3,    40)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (31, ''Daniel'',   ''Faviet'',    ''dfaviet'',     ''515-124-4169'', DATE ''2002-08-16'', ''ACCT'',     90000, NULL,  30,   40)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (32, ''John'',     ''Chen'',      ''jchen'',       ''515-124-4269'', DATE ''2005-09-28'', ''ACCT'',     88000, NULL,  30,   40)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (33, ''Ismael'',   ''Sciarra'',   ''isciarra'',    ''515-124-4369'', DATE ''2005-09-30'', ''ACCT'',     85000, NULL,  30,   40)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (34, ''Luis'',     ''Popp'',      ''lpopp'',       ''515-124-4567'', DATE ''2007-12-07'', ''ACCT'',     82000, NULL,  30,   40)';

  -- HR
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (40, ''Susan'',    ''Mavris'',    ''smavris'',     ''515-126-4562'', DATE ''2002-06-07'', ''MGR'',     110000, NULL,  1,    50)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (41, ''Hermann'',  ''Baer'',      ''hbaer'',       ''515-127-4563'', DATE ''2002-06-07'', ''HR_REP'',   72000, NULL,  40,   50)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (42, ''Shelley'',  ''Higgins'',   ''shiggins'',    ''515-128-4564'', DATE ''2002-06-07'', ''HR_REP'',   68000, NULL,  40,   50)';

  -- QA
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (50, ''William'',  ''Gietz'',     ''wgietz'',      ''515-129-4565'', DATE ''2002-06-07'', ''MGR'',     105000, NULL,  2,    60)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (51, ''Pat'',      ''Fay'',       ''pfay'',        ''603-123-6666'', DATE ''2005-08-17'', ''QA'',       85000, NULL,  50,   60)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (52, ''Michael'',  ''Hartstein'', ''mhartste'',    ''515-123-5555'', DATE ''2004-02-17'', ''QA'',       80000, NULL,  50,   60)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (53, ''Jennifer'', ''Whalen'',    ''jwhalen'',     ''515-123-4444'', DATE ''2003-09-17'', ''QA'',       76000, NULL,  50,   60)';

  -- Research
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (60, ''Den'',      ''Raphaely'',  ''draphael'',    ''515-127-4561'', DATE ''2002-12-07'', ''MGR'',     125000, NULL,  2,    70)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (61, ''Sigal'',    ''Tobias'',    ''stobias'',     ''515-127-4564'', DATE ''2005-07-24'', ''SR_DEV'',  115000, NULL,  60,   70)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (62, ''Guy'',      ''Himuro'',    ''ghimuro'',     ''515-127-4565'', DATE ''2006-11-15'', ''SR_DEV'',  110000, NULL,  60,   70)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (63, ''Karen'',    ''Colmenares'',''kcolmena'',    ''515-127-4566'', DATE ''2007-08-10'', ''DEV'',      90000, NULL,  61,   70)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (64, ''Shanta'',   ''Vollman'',   ''svollman'',    ''515-127-4567'', DATE ''2005-10-10'', ''DEV'',      86000, NULL,  61,   70)';

  -- Operations
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (70, ''Irene'',    ''Mikkilineni'',''imikkili'',   ''650-124-1224'', DATE ''2006-09-28'', ''MGR'',     115000, NULL,  2,    80)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (71, ''Jonathon'', ''Taylor'',    ''jtaylor'',     ''650-124-1334'', DATE ''2006-03-24'', ''DEV'',      95000, NULL,  70,   80)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (72, ''Jack'',     ''Livingston'',''jlivings'',    ''650-124-1444'', DATE ''2006-04-23'', ''DEV'',      92000, NULL,  70,   80)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (73, ''Kimberely'',''Grant'',     ''kgrant'',      ''650-124-1554'', DATE ''2007-05-24'', ''DEV'',      87000, NULL,  70,   80)';
  EXECUTE IMMEDIATE 'INSERT INTO employees VALUES (74, ''Charles'',  ''Johnson'',   ''cjohnson'',    ''650-124-1664'', DATE ''2008-01-04'', ''DEV'',      83000, NULL,  71,   80)';

  -- Update department managers
  EXECUTE IMMEDIATE 'UPDATE departments SET manager_id = 1  WHERE department_id = 10';
  EXECUTE IMMEDIATE 'UPDATE departments SET manager_id = 10 WHERE department_id = 20';
  EXECUTE IMMEDIATE 'UPDATE departments SET manager_id = 21 WHERE department_id = 30';
  EXECUTE IMMEDIATE 'UPDATE departments SET manager_id = 30 WHERE department_id = 40';
  EXECUTE IMMEDIATE 'UPDATE departments SET manager_id = 40 WHERE department_id = 50';
  EXECUTE IMMEDIATE 'UPDATE departments SET manager_id = 50 WHERE department_id = 60';
  EXECUTE IMMEDIATE 'UPDATE departments SET manager_id = 60 WHERE department_id = 70';
  EXECUTE IMMEDIATE 'UPDATE departments SET manager_id = 70 WHERE department_id = 80';

  -- Job history
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (11, DATE ''2001-06-17'', DATE ''2006-01-02'', ''DEV'',     20)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (12, DATE ''2001-01-13'', DATE ''2005-06-24'', ''DEV'',     20)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (13, DATE ''2004-03-24'', DATE ''2006-02-04'', ''QA'',      60)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (22, DATE ''2004-01-01'', DATE ''2007-03-18'', ''ANALYST'', 30)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (30, DATE ''1999-01-01'', DATE ''2002-08-16'', ''ACCT'',    40)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (31, DATE ''2000-01-01'', DATE ''2002-08-15'', ''ACCT'',    40)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (40, DATE ''2000-09-17'', DATE ''2002-06-06'', ''HR_REP'',  50)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (50, DATE ''2000-01-01'', DATE ''2002-06-06'', ''QA'',      60)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (60, DATE ''1998-03-24'', DATE ''2002-12-06'', ''DEV'',     20)';
  EXECUTE IMMEDIATE 'INSERT INTO job_history VALUES (61, DATE ''2001-10-28'', DATE ''2005-07-23'', ''DEV'',     70)';

  -- Audit log entries (simulating past changes)
  EXECUTE IMMEDIATE 'INSERT INTO audit_log VALUES (audit_seq.NEXTVAL, ''EMPLOYEES'', ''UPDATE'', ''ADMIN'', DATE ''2024-01-15'', ''salary=80000'', ''salary=85000'')';
  EXECUTE IMMEDIATE 'INSERT INTO audit_log VALUES (audit_seq.NEXTVAL, ''EMPLOYEES'', ''UPDATE'', ''ADMIN'', DATE ''2024-02-01'', ''salary=100000'', ''salary=105000'')';
  EXECUTE IMMEDIATE 'INSERT INTO audit_log VALUES (audit_seq.NEXTVAL, ''EMPLOYEES'', ''UPDATE'', ''HR_MGR'', DATE ''2024-02-15'', ''job=DEV'', ''job=SR_DEV'')';
  EXECUTE IMMEDIATE 'INSERT INTO audit_log VALUES (audit_seq.NEXTVAL, ''EMPLOYEES'', ''UPDATE'', ''HR_MGR'', DATE ''2024-03-01'', ''salary=75000'', ''salary=79000'')';
  EXECUTE IMMEDIATE 'INSERT INTO audit_log VALUES (audit_seq.NEXTVAL, ''DEPARTMENTS'', ''UPDATE'', ''ADMIN'', DATE ''2024-03-10'', ''manager_id=NULL'', ''manager_id=10'')';

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('demohr: Init complete — 8 tables, 4 sequences, 4 indexes, 1 view, 1 trigger, ~150 rows.');
END;
/

EXIT;
EOSQL

# =============================================================
# 4. demolegacy schema — Oracle-specific features (problematic migration)
# =============================================================
sqlplus -s demolegacy/demolegacy123@//localhost:1521/XEPDB1 <<'EOSQL'
SET SERVEROUTPUT ON
SET DEFINE OFF

DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'PRODUCTS';
  IF v_count > 0 THEN
    DBMS_OUTPUT.PUT_LINE('demolegacy: Tables already exist — skipping init.');
    RETURN;
  END IF;

  -- ==================== 1. TYPES ====================
  EXECUTE IMMEDIATE 'CREATE TYPE address_type AS OBJECT (
    street VARCHAR2(200), city VARCHAR2(100), state VARCHAR2(50), zip_code VARCHAR2(20), country VARCHAR2(50))';

  -- ==================== 2. SEQUENCES ====================
  EXECUTE IMMEDIATE 'CREATE SEQUENCE product_seq START WITH 100 INCREMENT BY 1';
  EXECUTE IMMEDIATE 'CREATE SEQUENCE order_seq START WITH 1000 INCREMENT BY 1';

  -- ==================== 3. CORE TABLES ====================
  EXECUTE IMMEDIATE 'CREATE TABLE products (
    product_id NUMBER PRIMARY KEY, product_name VARCHAR2(200) NOT NULL, price NUMBER(10,2) NOT NULL, category VARCHAR2(50))';

  EXECUTE IMMEDIATE 'CREATE TABLE customers (
    customer_id NUMBER PRIMARY KEY, customer_name VARCHAR2(200) NOT NULL, email VARCHAR2(100) UNIQUE,
    street VARCHAR2(200), city VARCHAR2(100), state VARCHAR2(50), zip_code VARCHAR2(20), country VARCHAR2(50))';

  EXECUTE IMMEDIATE 'CREATE TABLE orders (
    order_id NUMBER PRIMARY KEY, customer_id NUMBER NOT NULL REFERENCES customers(customer_id),
    order_date DATE DEFAULT SYSDATE, status VARCHAR2(20) DEFAULT ''PENDING'', total_amount NUMBER(12,2))';

  EXECUTE IMMEDIATE 'CREATE TABLE order_items (
    item_id NUMBER PRIMARY KEY, order_id NUMBER NOT NULL REFERENCES orders(order_id),
    product_id NUMBER NOT NULL REFERENCES products(product_id), quantity NUMBER(5) NOT NULL)';

  -- ==================== 4. VIEWS & MATERIALIZED VIEWS & GTT ====================
  EXECUTE IMMEDIATE 'CREATE VIEW customer_legacy_vw AS
    SELECT customer_id, customer_name, address_type(street, city, state, zip_code, country) AS formatted_address FROM customers';

  EXECUTE IMMEDIATE 'CREATE GLOBAL TEMPORARY TABLE session_cart (
    cart_id NUMBER, product_id NUMBER, quantity NUMBER, added_at DATE DEFAULT SYSDATE) ON COMMIT DELETE ROWS';

  EXECUTE IMMEDIATE 'CREATE MATERIALIZED VIEW legacy_mview_summary AS
    SELECT v.customer_id, COUNT(o.order_id) AS total_orders FROM customer_legacy_vw v LEFT JOIN orders o ON v.customer_id = o.customer_id GROUP BY v.customer_id';

  -- ==================== 5. PL/SQL (Functions, Procedures, Packages) ====================
  EXECUTE IMMEDIATE 'CREATE OR REPLACE FUNCTION get_order_total(p_order_id NUMBER) RETURN NUMBER IS
    v_total NUMBER; BEGIN SELECT SUM(p.price * oi.quantity) INTO v_total FROM order_items oi JOIN products p ON oi.product_id = p.product_id WHERE oi.order_id = p_order_id; RETURN NVL(v_total, 0); END;';

  EXECUTE IMMEDIATE 'CREATE OR REPLACE PROCEDURE update_order_status(p_order_id NUMBER, p_status VARCHAR2) IS
    BEGIN UPDATE orders SET status = p_status WHERE order_id = p_order_id; COMMIT; END;';

  EXECUTE IMMEDIATE 'CREATE OR REPLACE PACKAGE erp_sync_pkg AS
    PROCEDURE sync_customers; FUNCTION check_sync_status RETURN VARCHAR2; END erp_sync_pkg;';

  EXECUTE IMMEDIATE 'CREATE OR REPLACE PACKAGE BODY erp_sync_pkg AS
    PROCEDURE sync_customers IS BEGIN NULL; END; FUNCTION check_sync_status RETURN VARCHAR2 IS BEGIN RETURN ''OK''; END; END erp_sync_pkg;';

  -- ==================== 6. TRIGGERS ====================
  EXECUTE IMMEDIATE 'CREATE OR REPLACE TRIGGER trg_audit_orders
    AFTER INSERT ON orders FOR EACH ROW BEGIN DBMS_OUTPUT.PUT_LINE(''Order '' || :NEW.order_id || '' created.''); END;';

  -- ==================== 7. SYNONYMS ====================
  EXECUTE IMMEDIATE 'CREATE SYNONYM prd FOR products';
  EXECUTE IMMEDIATE 'CREATE SYNONYM cust FOR customers';

  -- ==================== 8. INDEXES (Function-based & Normal) ====================
  EXECUTE IMMEDIATE 'CREATE INDEX idx_cust_email_lower ON customers(LOWER(email))';
  EXECUTE IMMEDIATE 'CREATE INDEX idx_orders_status ON orders(status)';

  -- ==================== 9. DATABASE LINKS & JOBS (Wrapped in exception handlers to avoid grant issues) ====================
  BEGIN
    EXECUTE IMMEDIATE 'CREATE DATABASE LINK legacy_mainframe CONNECT TO dummy IDENTIFIED BY dummy USING ''MAINFRAME_DB''';
  EXCEPTION WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE('Skipped DB Link creation (insufficient privileges).');
  END;

  BEGIN
    DBMS_SCHEDULER.CREATE_JOB(
      job_name => 'DAILY_ERP_SYNC', job_type => 'PLSQL_BLOCK', job_action => 'BEGIN erp_sync_pkg.sync_customers; END;',
      start_date => SYSTIMESTAMP, repeat_interval => 'FREQ=DAILY', enabled => TRUE);
  EXCEPTION WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE('Skipped Job creation (insufficient privileges).');
  END;

  -- ==================== DATA ====================
  EXECUTE IMMEDIATE 'INSERT INTO products VALUES (product_seq.NEXTVAL, ''Laptop Pro 15'', 1499.99, ''Electronics'')';
  EXECUTE IMMEDIATE 'INSERT INTO products VALUES (product_seq.NEXTVAL, ''Wireless Mouse'', 49.99, ''Electronics'')';
  EXECUTE IMMEDIATE 'INSERT INTO customers VALUES (1, ''Acme Corporation'', ''admin@acme.com'', ''100 Industrial Pkwy'', ''Chicago'', ''IL'', ''60601'', ''US'')';
  EXECUTE IMMEDIATE 'INSERT INTO customers VALUES (2, ''TechStart Inc'', ''hello@techstart.io'', ''50 Innovation Dr'', ''San Jose'', ''CA'', ''95110'', ''US'')';
  EXECUTE IMMEDIATE 'INSERT INTO orders (order_id, customer_id, status, total_amount) VALUES (order_seq.NEXTVAL, 1, ''DELIVERED'', 1549.98)';
  EXECUTE IMMEDIATE 'INSERT INTO orders (order_id, customer_id, status, total_amount) VALUES (order_seq.NEXTVAL, 2, ''DELIVERED'', 449.99)';
  EXECUTE IMMEDIATE 'INSERT INTO order_items VALUES (1, 1000, 100, 1)';
  EXECUTE IMMEDIATE 'INSERT INTO order_items VALUES (2, 1000, 101, 1)';
  EXECUTE IMMEDIATE 'INSERT INTO order_items VALUES (3, 1001, 101, 10)';

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('demolegacy: Init complete — Assessment report objects generated.');
END;
/

EXIT;
EOSQL

# =============================================================
# 5. demominor schema — Minor issue (Implicit Cast in Check Constraint)
# =============================================================
sqlplus -s demominor/demominor123@//localhost:1521/XEPDB1 <<'EOSQL'
SET SERVEROUTPUT ON

DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'PROJECTS';
  IF v_count > 0 THEN
    DBMS_OUTPUT.PUT_LINE('demominor: Tables already exist — skipping init.');
    RETURN;
  END IF;

  -- Sequence
  EXECUTE IMMEDIATE 'CREATE SEQUENCE proj_seq START WITH 100 INCREMENT BY 1';

  -- Table with a VARCHAR2 column meant to hold numbers
  EXECUTE IMMEDIATE 'CREATE TABLE projects (
    project_id   NUMBER PRIMARY KEY,
    project_name VARCHAR2(100) NOT NULL,
    budget       NUMBER(10,2),
    project_code VARCHAR2(10) NOT NULL)';

  -- The Trap: Added via ALTER TABLE so the base table survives.
  -- Oracle allows comparing VARCHAR2 to NUMBER. Postgres will reject this syntax.
  EXECUTE IMMEDIATE 'ALTER TABLE projects ADD CONSTRAINT chk_proj_code_numeric CHECK (project_code > 0)';

  -- Dependent table to prove FKs still work
  EXECUTE IMMEDIATE 'CREATE TABLE tasks (
    task_id     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  NUMBER REFERENCES projects(project_id),
    task_name   VARCHAR2(200) NOT NULL,
    hours_spent NUMBER DEFAULT 0)';

  -- Insert Data
  EXECUTE IMMEDIATE 'INSERT INTO projects (project_id, project_name, budget, project_code) VALUES (proj_seq.NEXTVAL, ''Ora2Pg Web UI'', 5000, ''101'')';
  EXECUTE IMMEDIATE 'INSERT INTO projects (project_id, project_name, budget, project_code) VALUES (proj_seq.NEXTVAL, ''Postgres Validation'', 8000, ''102'')';

  EXECUTE IMMEDIATE 'INSERT INTO tasks (project_id, task_name, hours_spent) VALUES (100, ''Setup Spring Boot wrapper'', 12)';
  EXECUTE IMMEDIATE 'INSERT INTO tasks (project_id, task_name, hours_spent) VALUES (100, ''Configure SSE Emitter'', 5)';
  EXECUTE IMMEDIATE 'INSERT INTO tasks (project_id, task_name, hours_spent) VALUES (101, ''Test constraint failures'', 2)';

  COMMIT;
  DBMS_OUTPUT.PUT_LINE('demominor: Init complete — 2 tables, 1 constraint trap, 5 rows.');
END;
/

EXIT;
EOSQL

echo "=== All Oracle schemas initialized ==="
