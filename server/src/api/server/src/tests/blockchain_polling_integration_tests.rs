use crate::tests::{
    gen_services,
    lib::{create_account, create_default_account_with_predefined_wallet},
    requests::{axum::TestClient, worker::TestWorker},
};
use account::entities::FullAccount;
use account::{entities::TouchpointPlatform, service::AddPushTouchpointToAccountInput};
use httpmock::{prelude::*, Mock, MockExt};
use notification::address_repo::AddressAndKeysetId;
use notification::service::FetchForAccountInput;
use notification::service::Service as NotificationService;
use notification::NotificationPayloadType;
use std::collections::{HashMap, HashSet};
use types::{
    account::identifiers::{AccountId, KeysetId},
    notification::{NotificationChannel, NotificationsPreferences},
};

struct ChainMockData {
    tip_hash_mock_id: Option<usize>,
    expected_tip_hash_hits: usize,
    tip_hash: &'static str,
    blocks: HashMap<&'static str, BlockMockData>,
}

struct BlockMockData {
    raw_block_mock_id: usize,
    expected_hit: bool,
    prev_hash: &'static str,
}

struct BlockHeader {
    block_hash: &'static str,
    prev_hash: &'static str,
}

// TODO: add more test cases: W-3244/add-more-test-cases-for-blockchain-polling-job
#[tokio::test]
async fn test_init_block_received_payment() {
    let (mock_server, account, worker, notification_service, mut chain_mock_data) =
        setup_full_accounts_and_server().await;

    // Initializing service with block 107036
    run_and_test_blockchain_polling_worker(
        &mock_server,
        &worker,
        &mut chain_mock_data,
        "00000049405168aecc9bdc996f2d35ae8f7855685dfd4c6513f68679428cdbfe",
    )
    .await;

    // The account should have received 1 payment notification for block 107036
    test_queue_message(&notification_service, &account.id, 1).await;

    // Running again with block 107036
    run_and_test_blockchain_polling_worker(
        &mock_server,
        &worker,
        &mut chain_mock_data,
        "00000049405168aecc9bdc996f2d35ae8f7855685dfd4c6513f68679428cdbfe",
    )
    .await;

    // The account should no longer receive payment notifications
    test_queue_message(&notification_service, &account.id, 1).await;

    // Advancing to block 107038
    run_and_test_blockchain_polling_worker(
        &mock_server,
        &worker,
        &mut chain_mock_data,
        "0000012b9852f41934927b43ebac7354b10627f17ebc85e1de6bae593312591a",
    )
    .await;

    // The account should still not receive payment notifications
    test_queue_message(&notification_service, &account.id, 1).await;

    // Serving old block 107035
    run_and_test_blockchain_polling_worker(
        &mock_server,
        &worker,
        &mut chain_mock_data,
        "00000091c3089d71ac2ed150c18cfb96898cb0a63e5a4695cd79536de452e5fa",
    )
    .await;

    // The account should still not receive payment notifications
    test_queue_message(&notification_service, &account.id, 1).await;

    // Reverting back to block 107038
    run_and_test_blockchain_polling_worker(
        &mock_server,
        &worker,
        &mut chain_mock_data,
        "0000012b9852f41934927b43ebac7354b10627f17ebc85e1de6bae593312591a",
    )
    .await;

    // The account should still not receive payment notifications
    test_queue_message(&notification_service, &account.id, 1).await;
}

async fn setup_full_accounts_and_server() -> (
    MockServer,
    FullAccount,
    TestWorker,
    NotificationService,
    ChainMockData,
) {
    let mock_server = MockServer::start();
    let bootstrap = gen_services().await;
    let client = TestClient::new(bootstrap.router.clone()).await;
    let (account_with_payment, _) =
        create_default_account_with_predefined_wallet(&client, &bootstrap.services).await;
    let account_without_payment = create_account(
        &bootstrap.services,
        account::entities::Network::BitcoinSignet,
        None,
    )
    .await;
    let state = workers::jobs::WorkerState {
        config: http_server::config::extract(None).unwrap(),
        notification_service: bootstrap.services.notification_service.clone(),
        account_service: bootstrap.services.account_service.clone(),
        recovery_service: bootstrap.services.recovery_service.clone(),
        address_repo: bootstrap.services.address_repo.clone(),
        chain_indexer_service: bootstrap
            .services
            .chain_indexer_service
            .clone()
            .set_mock_server(mock_server.base_url()),
        sqs: bootstrap.services.sqs.clone(),
        feature_flags_service: bootstrap.services.feature_flags_service.clone(),
    };
    state
        .account_service
        .add_push_touchpoint_for_account(AddPushTouchpointToAccountInput {
            account_id: account_with_payment.id.clone(),
            use_local_sns: true,
            platform: TouchpointPlatform::ApnsTeam,
            device_token: "test".to_string(),
            access_token: Default::default(),
        })
        .await
        .unwrap();
    client
        .set_notifications_preferences(
            &account_with_payment.id.to_string(),
            &NotificationsPreferences {
                account_security: HashSet::default(),
                money_movement: HashSet::from([NotificationChannel::Push]),
                product_marketing: HashSet::new(),
            },
            false,
            false,
        )
        .await;
    state
        .account_service
        .add_push_touchpoint_for_account(AddPushTouchpointToAccountInput {
            account_id: account_without_payment.id.clone(),
            use_local_sns: true,
            platform: TouchpointPlatform::ApnsTeam,
            device_token: "test".to_string(),
            access_token: Default::default(),
        })
        .await
        .unwrap();
    client
        .set_notifications_preferences(
            &account_without_payment.id.to_string(),
            &NotificationsPreferences {
                account_security: HashSet::default(),
                money_movement: HashSet::from([NotificationChannel::Push]),
                product_marketing: HashSet::new(),
            },
            false,
            false,
        )
        .await;

    // Add destination address from mocked block 107036 to watchlist table.
    // Note, these addresses aren't actually derivable from the account's bdk wallet.
    let fake_registration = AddressAndKeysetId::new(
        "tb1p2u3xcjt9x64s9u3lqwfndn5td5dkasf7amz0h7643k5ds9vvvacq7dvf7k"
            .parse()
            .unwrap(),
        KeysetId::gen().unwrap(),
    );
    bootstrap
        .services
        .address_repo
        .clone()
        .insert(&[fake_registration], &account_with_payment.id)
        .await
        .unwrap();

    let worker = TestWorker::new(state).await;

    let chain_mock_data = setup_raw_block_mocks(
        &mock_server,
        vec![
            BlockHeader {
                block_hash: "00000091c3089d71ac2ed150c18cfb96898cb0a63e5a4695cd79536de452e5fa", // 107035
                prev_hash: "00000086a8b5a64017cbcc88db2e78999a75ce2f2924a665d60b607c753d297b",
            },
            BlockHeader {
                block_hash: "00000049405168aecc9bdc996f2d35ae8f7855685dfd4c6513f68679428cdbfe", // 107036
                prev_hash: "00000091c3089d71ac2ed150c18cfb96898cb0a63e5a4695cd79536de452e5fa",
            },
            BlockHeader {
                block_hash: "000000d3d12016125e10320dc1e2b3a719266c48313c8529d812cd58b190d0d4", // 107037
                prev_hash: "00000049405168aecc9bdc996f2d35ae8f7855685dfd4c6513f68679428cdbfe",
            },
            BlockHeader {
                block_hash: "0000012b9852f41934927b43ebac7354b10627f17ebc85e1de6bae593312591a", // 107038
                prev_hash: "000000d3d12016125e10320dc1e2b3a719266c48313c8529d812cd58b190d0d4",
            },
        ],
    );

    (
        mock_server,
        account_with_payment,
        worker,
        bootstrap.services.notification_service,
        chain_mock_data,
    )
}

fn setup_raw_block_mocks(
    mock_server: &MockServer,
    block_headers: Vec<BlockHeader>,
) -> ChainMockData {
    let mut chain_mock_data = ChainMockData {
        tip_hash_mock_id: None,
        tip_hash: "",
        expected_tip_hash_hits: 0,
        blocks: HashMap::new(),
    };

    for BlockHeader {
        block_hash,
        prev_hash,
    } in block_headers
    {
        let raw_block_mock = mock_server.mock(|when, then| {
            when.method(GET).path(format!("/block/{block_hash}/raw"));
            then.status(200)
                .header("content-type", "application/octet-stream")
                .body_from_file(format!("src/tests/raw_blocks/{block_hash}.bin"));
        });

        chain_mock_data.blocks.insert(
            block_hash,
            BlockMockData {
                raw_block_mock_id: raw_block_mock.id(),
                expected_hit: false,
                prev_hash,
            },
        );
    }

    chain_mock_data
}

async fn run_and_test_blockchain_polling_worker(
    mock_server: &MockServer,
    worker: &TestWorker,
    chain_mock_data: &mut ChainMockData,
    tip_hash: &'static str,
) {
    if chain_mock_data.tip_hash != tip_hash {
        // The raw block for the tip hash should be fetched once if it is not already in the DB.
        chain_mock_data
            .blocks
            .get_mut(tip_hash)
            .unwrap()
            .expected_hit = true;
        if chain_mock_data.tip_hash_mock_id.is_some() {
            // Delete current tip hash mock so that it can be replaced with a new one
            Mock::new(chain_mock_data.tip_hash_mock_id.unwrap(), mock_server).delete();

            let mut prev_hash = chain_mock_data.blocks.get(tip_hash).unwrap().prev_hash;
            while chain_mock_data.tip_hash != prev_hash {
                if let Some(prev_block) = chain_mock_data.blocks.get_mut(prev_hash) {
                    // We should expect to have fetched each block that is not already in the DB
                    // between our current tip and the new tip at least, and at most, once.
                    prev_block.expected_hit = true;
                    prev_hash = prev_block.prev_hash;
                } else {
                    // We don't have a mock for the prior block, so there are no more mocks to
                    // increment.
                    break;
                }
            }
        }
        // Create new tip hash mock
        let tip_hash_mock = mock_server.mock(|when, then| {
            when.method(GET).path("/blocks/tip/hash");
            then.status(200)
                .header("content-type", "text/plain")
                .body(tip_hash);
        });
        chain_mock_data.tip_hash_mock_id = Some(tip_hash_mock.id());
        chain_mock_data.tip_hash = tip_hash;
        chain_mock_data.expected_tip_hash_hits = 0;
    }

    // Run the job
    worker.blockchain_polling().await;
    // The tip hash should be fetched once on every run of the job.
    chain_mock_data.expected_tip_hash_hits += 1;
    // Check expected hits
    Mock::new(chain_mock_data.tip_hash_mock_id.unwrap(), mock_server)
        .assert_hits(chain_mock_data.expected_tip_hash_hits);
    for (
        _,
        BlockMockData {
            raw_block_mock_id,
            expected_hit,
            ..
        },
    ) in chain_mock_data.blocks.iter()
    {
        // Block mocks are always expected to have hit either once or not at all, because once they
        // have been saved to the DB they should not be fetched again.
        Mock::new(*raw_block_mock_id, mock_server).assert_hits(*expected_hit as usize);
    }
}

async fn test_queue_message(
    notification_service: &NotificationService,
    account_id: &AccountId,
    aggregate_message_count: usize,
) {
    let scheduled_notifications = notification_service
        .fetch_scheduled_for_account(FetchForAccountInput {
            account_id: account_id.clone(),
        })
        .await
        .unwrap();
    assert_eq!(
        scheduled_notifications.len(),
        0,
        "{:?}",
        scheduled_notifications
    );

    let notifications = notification_service
        .fetch_customer_for_account(FetchForAccountInput {
            account_id: account_id.clone(),
        })
        .await
        .unwrap();

    assert_eq!(
        notifications.len(),
        aggregate_message_count,
        "{:?}",
        notifications
    );
    assert!(
        notifications
            .iter()
            .all(|n| n.payload_type == NotificationPayloadType::PaymentNotification),
        "{:?}",
        notifications
    );
    assert!(
        notifications.iter().all(|n| n.account_id == *account_id),
        "{:?}",
        notifications
    );
}
