-- Immutable schema fixture from phase 5 (user_version 2).
CREATE TABLE sessions(id TEXT PRIMARY KEY, root TEXT NOT NULL, backend TEXT NOT NULL,
            state TEXT NOT NULL, phase TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
            provisional INTEGER NOT NULL DEFAULT 1, started INTEGER NOT NULL, ended INTEGER NOT NULL DEFAULT 0,
            nodes INTEGER NOT NULL DEFAULT 0, issues INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '',
            scanner_uid INTEGER NOT NULL, scanner_pid INTEGER NOT NULL, metrics TEXT NOT NULL DEFAULT '{}');
CREATE TABLE nodes(sid TEXT NOT NULL REFERENCES sessions(id), id INTEGER NOT NULL,
            parent INTEGER NOT NULL, depth INTEGER NOT NULL, name BLOB NOT NULL, name_key TEXT NOT NULL,
            path BLOB NOT NULL, link BLOB NOT NULL, kind INTEGER NOT NULL, mode INTEGER NOT NULL,
            uid INTEGER NOT NULL, gid INTEGER NOT NULL, device BLOB NOT NULL, inode BLOB NOT NULL,
            mount INTEGER NOT NULL, nlink INTEGER NOT NULL, size INTEGER NOT NULL, allocated INTEGER NOT NULL,
            seconds INTEGER NOT NULL, nanos INTEGER NOT NULL, valid INTEGER NOT NULL, complete INTEGER NOT NULL,
            enumerated INTEGER NOT NULL DEFAULT 0, skip INTEGER NOT NULL, PRIMARY KEY(sid,id)) WITHOUT ROWID;
CREATE UNIQUE INDEX node_path ON nodes(sid,path);
CREATE INDEX node_parent ON nodes(sid,parent,id);
CREATE INDEX node_identity ON nodes(sid,device,inode,path);
CREATE TABLE aggregates(sid TEXT NOT NULL REFERENCES sessions(id), id INTEGER NOT NULL,
            parent INTEGER NOT NULL, depth INTEGER NOT NULL, kind INTEGER NOT NULL, name_key TEXT NOT NULL,
            owner INTEGER NOT NULL, own_logical INTEGER NOT NULL, own_allocated INTEGER NOT NULL,
            logical INTEGER NOT NULL, allocated INTEGER NOT NULL, complete INTEGER NOT NULL,
            PRIMARY KEY(sid,id)) WITHOUT ROWID;
CREATE INDEX aggregate_parent ON aggregates(sid,parent,id);
CREATE INDEX aggregate_incomplete ON aggregates(sid,parent,complete);
CREATE INDEX aggregate_depth ON aggregates(sid,depth,id);
CREATE INDEX children_logical ON aggregates(sid,parent,logical DESC,id DESC);
CREATE INDEX children_allocated ON aggregates(sid,parent,allocated DESC,id DESC);
CREATE INDEX children_name ON aggregates(sid,parent,name_key,id);
CREATE INDEX large_logical ON aggregates(sid,kind,logical DESC,id DESC);
CREATE INDEX large_allocated ON aggregates(sid,kind,allocated DESC,id DESC);
CREATE TABLE hardlinks(sid TEXT NOT NULL, device BLOB NOT NULL, inode BLOB NOT NULL,
            path BLOB NOT NULL, changed INTEGER NOT NULL, PRIMARY KEY(sid,device,inode)) WITHOUT ROWID;
CREATE TABLE issues(id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT NOT NULL REFERENCES sessions(id),
            node INTEGER NOT NULL, code INTEGER NOT NULL, errno INTEGER NOT NULL, operation TEXT NOT NULL);
CREATE INDEX issue_session ON issues(sid,id);
CREATE INDEX issue_node ON issues(sid,node,id);
CREATE TABLE snapshots(id TEXT PRIMARY KEY, sid TEXT NOT NULL UNIQUE REFERENCES sessions(id),
            revision INTEGER NOT NULL, created INTEGER NOT NULL, logical INTEGER NOT NULL, allocated INTEGER NOT NULL,
            complete INTEGER NOT NULL);