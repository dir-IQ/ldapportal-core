-- SPDX-License-Identifier: Apache-2.0
-- Add a per-connection max pooled-connection age so connections are recycled
-- before an intermediary (firewall / load balancer idle timeout, e.g. the
-- default 350s on an AWS NLB) or the server's own idle-time limit silently
-- closes them. Without this, an idle pooled connection the server has already
-- dropped stays in the pool until it's borrowed, and the next operation fails
-- with "connection reset" against an otherwise-healthy directory. Default 300s
-- sits below the common 350s NLB timeout; 0 disables the cap.

ALTER TABLE directory_connections
    ADD COLUMN pool_max_connection_age_seconds integer DEFAULT 300 NOT NULL;
