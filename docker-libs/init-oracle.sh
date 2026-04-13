#!/bin/bash
sqlplus -s testuser/testuser123@//localhost:1521/XEPDB1 <<'EOSQL'
SET SERVEROUTPUT ON

DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'DEPARTMENTS';
  IF v_count > 0 THEN
    DBMS_OUTPUT.PUT_LINE('Tables already exist — skipping init.');
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
  DBMS_OUTPUT.PUT_LINE('Init complete — 3 tables, 12 rows.');
END;
/

EXIT;
EOSQL
