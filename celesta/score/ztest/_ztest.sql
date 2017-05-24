create grain ztest version '1.0';

create table tableForGetDateInView (
  id int identity not null primary key,
  date datetime
);

create view viewWithGetDate as
 select id from tableForGetDateInView where date > getdate();

create table tableCountWithoutCondition (
  id int identity not null primary key,
  date datetime
);

create table tableCountAndGetDateCondition (
  id int identity not null primary key,
  date datetime
);

create view viewCountWithoutCondition as
 select count(*) as c from tableCountWithoutCondition;

create view viewCountAndGetDateCondition as
 select count(*) as c from tableCountAndGetDateCondition where date > getdate();

 create table zeroInsert (
  id int identity not null primary key,
  date datetime not null default getdate()
 ) with no version check;
 
CREATE TABLE simple_table(
  id INT NOT NULL IDENTITY ,
  name VARCHAR(255) NOT NULL,
  CONSTRAINT Pk_simple_table PRIMARY KEY (id)
);

