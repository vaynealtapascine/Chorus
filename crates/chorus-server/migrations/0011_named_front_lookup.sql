-- A legacy-only current-front read must not scan the account's complete switch history.
CREATE INDEX op_named_front_scope ON op(scope)
  WHERE status='applied' AND kind IN ('front.define','front.layer','front.change');
