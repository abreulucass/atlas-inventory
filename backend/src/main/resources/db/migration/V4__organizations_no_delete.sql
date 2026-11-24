-- Organizations are archived, never deleted: deleting one would free its slug (ORG-08).
revoke delete on organizations from atlas_app;
