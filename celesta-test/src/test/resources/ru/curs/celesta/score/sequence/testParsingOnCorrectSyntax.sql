CREATE GRAIN test VERSION '1.0';

CREATE SEQUENCE s1;
CREATE SEQUENCE s2 START WITH 3;
CREATE SEQUENCE s3 INCREMENT BY 2;
CREATE SEQUENCE s4 MINVALUE 5;
CREATE SEQUENCE s5 MAXVALUE 56;
/**TEST*/
CREATE SEQUENCE s6 CYCLE;

CREATE SEQUENCE s7 START WITH 3 INCREMENT BY 2 MINVALUE 5 MAXVALUE 56 CYCLE;
CREATE SEQUENCE s8 START WITH 3 CYCLE MAXVALUE 56 INCREMENT BY 2 MINVALUE 5;