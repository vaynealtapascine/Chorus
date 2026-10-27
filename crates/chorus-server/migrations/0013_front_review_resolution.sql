-- Card creation and late resolution replay must not scan an account's switch history.
CREATE INDEX op_front_review_choice ON op(scope,json_extract(payload,'$.review_id'),seq)
  WHERE kind='front.review_resolve' AND status='applied';
-- db::migrate repairs existing derived choices with the same core rule used by live projection.
