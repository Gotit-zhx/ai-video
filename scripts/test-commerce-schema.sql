-- Run only against a disposable, empty MySQL test database.
INSERT INTO commerce_template_versions VALUES
('snapshot1','org1','template1',1,'WORKFLOW','test','{}','user1',NOW(6)),
('snapshot2','org1','template1',2,'WORKFLOW','test v2','{}','user1',NOW(6)),
('snapshot3','org2','template1',1,'WORKFLOW','foreign','{}','user2',NOW(6));
INSERT INTO commerce_batches VALUES
('batch1','org1','test','snapshot1','request-key','hash','DRAFT','user1',NOW(6));
INSERT INTO commerce_batch_items VALUES
('item1','org1','batch1','capture1',4,'shop1','test shop',1,'DRAFT',NOW(6));
DELIMITER //
CREATE PROCEDURE verify_commerce_schema()
BEGIN
  DECLARE duplicate_blocked BOOLEAN DEFAULT FALSE;
  DECLARE foreign_key_blocked BOOLEAN DEFAULT FALSE;
  IF (SELECT COUNT(*) FROM commerce_template_versions WHERE organization_id='org1') <> 2 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='template versions missing';
  END IF;
  IF (SELECT template_snapshot_id FROM commerce_batches WHERE id='batch1') <> 'snapshot1' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='batch not pinned to original version';
  END IF;
  BEGIN
    DECLARE CONTINUE HANDLER FOR 1062 SET duplicate_blocked=TRUE;
    INSERT INTO commerce_template_versions VALUES
    ('duplicate','org1','template1',2,'WORKFLOW','bad','{}','user1',NOW(6));
  END;
  IF NOT duplicate_blocked THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='duplicate version allowed'; END IF;
  SET duplicate_blocked=FALSE;
  BEGIN
    DECLARE CONTINUE HANDLER FOR 1062 SET duplicate_blocked=TRUE;
    INSERT INTO commerce_batches VALUES
    ('batch2','org1','test','snapshot1','request-key','hash','DRAFT','user1',NOW(6));
  END;
  IF NOT duplicate_blocked THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='duplicate request allowed'; END IF;
  SET duplicate_blocked=FALSE;
  BEGIN
    DECLARE CONTINUE HANDLER FOR 1062 SET duplicate_blocked=TRUE;
    INSERT INTO commerce_batch_items VALUES
    ('item2','org1','batch1','capture1',4,'shop1','test shop',1,'DRAFT',NOW(6));
  END;
  IF NOT duplicate_blocked THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='duplicate target allowed'; END IF;
  BEGIN
    DECLARE CONTINUE HANDLER FOR 1452 SET foreign_key_blocked=TRUE;
    INSERT INTO commerce_batches VALUES
    ('batch3','org1','test','nonexistent','request-key3','hash','DRAFT','user1',NOW(6));
  END;
  IF NOT foreign_key_blocked THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='missing snapshot allowed'; END IF;
  SELECT 'commerce schema constraints passed' AS result;
END//
DELIMITER ;
CALL verify_commerce_schema();
