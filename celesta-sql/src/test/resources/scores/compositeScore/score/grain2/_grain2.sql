create grain grain2 version '1.0';

create sequence b_idb;

create table b (
idb int not null default nextval(b_idb) primary key,
descr varchar(2),
ida int foreign key references grain1.a(ida)
);