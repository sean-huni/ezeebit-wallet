-- One database and one owning role per service: neither service can reach the other's database.
CREATE ROLE ledger LOGIN PASSWORD 'ledger';
CREATE DATABASE ledger OWNER ledger;
REVOKE CONNECT ON DATABASE ledger FROM PUBLIC;

CREATE ROLE conversion LOGIN PASSWORD 'conversion';
CREATE DATABASE conversion OWNER conversion;
REVOKE CONNECT ON DATABASE conversion FROM PUBLIC;
