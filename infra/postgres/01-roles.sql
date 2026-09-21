\set ON_ERROR_STOP on
\getenv commerce_password COMMERCE_DB_PASSWORD
\getenv agent_password AGENT_DB_PASSWORD
\getenv voc_password VOC_DB_PASSWORD
\getenv evidence_password EVIDENCE_DB_PASSWORD
\getenv db_name POSTGRES_DB

SELECT format('CREATE ROLE jdd_commerce LOGIN PASSWORD %L', :'commerce_password') \gexec
SELECT format('CREATE ROLE jdd_agent LOGIN PASSWORD %L', :'agent_password') \gexec
SELECT format('CREATE ROLE jdd_voc LOGIN PASSWORD %L', :'voc_password') \gexec
SELECT format('CREATE ROLE jdd_evidence LOGIN PASSWORD %L', :'evidence_password') \gexec

REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO jdd_commerce, jdd_agent, jdd_voc, jdd_evidence;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA commerce AUTHORIZATION jdd_commerce;
CREATE SCHEMA agent AUTHORIZATION jdd_agent;
CREATE SCHEMA voc AUTHORIZATION jdd_voc;
GRANT USAGE ON SCHEMA commerce TO jdd_evidence;
ALTER DEFAULT PRIVILEGES FOR ROLE jdd_commerce IN SCHEMA commerce
    GRANT SELECT ON TABLES TO jdd_evidence;
ALTER ROLE jdd_evidence SET default_transaction_read_only TO on;
