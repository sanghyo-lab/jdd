ALTER TABLE agent.demo_budget ADD COLUMN maximum_calls INTEGER CHECK (maximum_calls > 0);
