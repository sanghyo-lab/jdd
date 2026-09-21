CREATE TABLE bootstrap_probe (
    id VARCHAR(32) PRIMARY KEY,
    service_name VARCHAR(64) NOT NULL
);
INSERT INTO bootstrap_probe (id, service_name) VALUES ('bootstrap', 'voc-app');
