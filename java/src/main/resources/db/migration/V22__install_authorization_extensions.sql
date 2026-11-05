-- The pending-authorization and VSAM/MQ extensions join V7's Db2 extension in the default region, now that
-- V20 and V21 hold their segments, queues and triggers.

UPDATE cics_region SET installed = array_cat(installed, '{pending-authorizations,vsam-mq}');
