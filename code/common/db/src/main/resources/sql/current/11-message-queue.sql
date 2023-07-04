CREATE TABLE PROC_MESSAGE(
    ID              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Unique id',

    RELATED_ID      BIGINT NOT NULL DEFAULT -1 COMMENT 'Unique id a related message',
    SENDER_INBOX    VARCHAR(255)          COMMENT 'Name of the sender inbox',

    RECIPIENT_INBOX VARCHAR(255) NOT NULL COMMENT 'Name of the recipient inbox',
    FUNCTION        VARCHAR(255) NOT NULL COMMENT 'Which function to run',
    PAYLOAD         TEXT                  COMMENT 'Message to recipient',

    OWNER_INSTANCE  VARCHAR(255)          COMMENT 'Instance UUID corresponding to the party that has claimed the message',
    OWNER_TICK      BIGINT  DEFAULT -1    COMMENT 'Used by recipient to determine which messages it has processed',

    STATE           ENUM('NEW', 'ACK', 'OK', 'ERR', 'DEAD')
                    NOT NULL DEFAULT 'NEW' COMMENT 'Processing state',

    CREATED_TIME    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Time of creation',
    UPDATED_TIME    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Time of last update',
    TTL             INT              COMMENT 'Time to live in seconds'
);
