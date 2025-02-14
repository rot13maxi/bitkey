use crate::GenServiceOverrides;
use account::entities::Network::BitcoinSignet;
use bdk_utils::bdk::bitcoin::address::NetworkUnchecked;
use bdk_utils::bdk::bitcoin::Address;
use database::ddb;
use http::StatusCode;
use http_server::config;
use notification::address_repo::ddb::service::Service as AddressRepoDDB;
use notification::address_repo::memory::Service as AddressRepoMemory;
use notification::address_repo::{AddressAndKeysetId, AddressWatchlistTrait};
use rstest::{fixture, rstest};
use types::account::identifiers::{AccountId, KeysetId};

use crate::tests::gen_services_with_overrides;
use crate::tests::lib::create_account;
use crate::tests::requests::axum::TestClient;

fn memory_repo() -> impl AddressWatchlistTrait + Clone {
    AddressRepoMemory::default()
}

async fn ddb_repo() -> impl AddressWatchlistTrait + Clone {
    let conn = config::extract::<ddb::Config>(Some("test"))
        .unwrap()
        .to_connection()
        .await;
    AddressRepoDDB::create(conn).await.unwrap()
}

struct TestContext<A: AddressWatchlistTrait + Clone + 'static> {
    address_repo: A,
    known_account_id_1: AccountId,
    known_account_id_2: AccountId,
    client: TestClient,
}

impl<A: AddressWatchlistTrait + Clone + 'static> TestContext<A> {
    async fn set_up(address_repo: A) -> Self {
        let overrides = GenServiceOverrides::new().address_repo(Box::new(address_repo.clone()));
        let bootstrap = gen_services_with_overrides(overrides).await;
        let client = TestClient::new(bootstrap.router).await;
        let account_1 = create_account(&bootstrap.services, BitcoinSignet, None).await;
        let account_2 = create_account(&bootstrap.services, BitcoinSignet, None).await;

        Self {
            address_repo,
            client,
            known_account_id_1: account_1.id,
            known_account_id_2: account_2.id,
        }
    }
}

#[fixture]
fn addr1() -> AddressAndKeysetId {
    AddressAndKeysetId::new(
        "bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs".parse().unwrap(),
        KeysetId::gen().unwrap(),
    )
}

#[fixture]
fn addr2() -> AddressAndKeysetId {
    AddressAndKeysetId::new(
        "bc1q42lja79elem0anu8q8s3h2n687re9jax556pcc"
            .parse()
            .unwrap(),
        KeysetId::gen().unwrap(),
    )
}

async fn get_acct_for_addr(
    repo: &impl AddressWatchlistTrait,
    addr: &Address<NetworkUnchecked>,
) -> AccountId {
    repo.get(&[addr.clone()])
        .await
        .unwrap()
        .get(addr)
        .unwrap()
        .clone()
}

#[rstest]
#[tokio::test]
async fn test_register_watch_address_with_unknown_account_id_404(
    addr1: AddressAndKeysetId,
    #[values(memory_repo(), ddb_repo().await)] repo: impl AddressWatchlistTrait + Clone + 'static,
) {
    let test_ctx = TestContext::set_up(repo).await;
    let acct_id = AccountId::gen().ok().unwrap();

    let response = test_ctx
        .client
        .register_watch_address(&acct_id, &vec![addr1.clone()].into())
        .await;

    assert_eq!(StatusCode::NOT_FOUND, response.status_code);
}

#[rstest]
#[tokio::test]
async fn test_register_watch_address_with_known_account_id_first_insert_200(
    addr1: AddressAndKeysetId,
    #[values(memory_repo(), ddb_repo().await)] repo: impl AddressWatchlistTrait + Clone + 'static,
) {
    let test_ctx = TestContext::set_up(repo).await;
    let acct_id = test_ctx.known_account_id_1;

    let response = test_ctx
        .client
        .register_watch_address(&acct_id, &vec![addr1.clone()].into())
        .await;

    assert_eq!(StatusCode::OK, response.status_code);
    assert_eq!(
        acct_id,
        get_acct_for_addr(&test_ctx.address_repo, &addr1.address).await
    );
}

#[rstest]
#[tokio::test]
async fn test_register_watch_address_with_known_account_id_multiple_insert_200(
    addr1: AddressAndKeysetId,
    addr2: AddressAndKeysetId,
    #[values(memory_repo(), ddb_repo().await)] repo: impl AddressWatchlistTrait + Clone + 'static,
) {
    let test_ctx = TestContext::set_up(repo).await;
    let acct_id = test_ctx.known_account_id_1;

    let response = test_ctx
        .client
        .register_watch_address(&acct_id, &vec![addr1.clone(), addr2.clone()].into())
        .await;

    assert_eq!(StatusCode::OK, response.status_code);

    let known = test_ctx
        .address_repo
        .get(&[addr1.address.clone(), addr2.address.clone()])
        .await
        .unwrap();

    assert_eq!(&acct_id, known.get(&addr1.address).unwrap());
    assert_eq!(&acct_id, known.get(&addr2.address).unwrap());
}

#[rstest]
#[tokio::test]
async fn test_register_watch_address_with_known_account_id_second_insert_200(
    addr1: AddressAndKeysetId,
    #[values(memory_repo(), ddb_repo().await)] repo: impl AddressWatchlistTrait + Clone + 'static,
) {
    let test_ctx = TestContext::set_up(repo).await;
    let acct_id = test_ctx.known_account_id_1;

    let _ = test_ctx
        .client
        .register_watch_address(&acct_id, &vec![addr1.clone()].into())
        .await;

    let response = test_ctx
        .client
        .register_watch_address(&acct_id, &vec![addr1.clone()].into())
        .await;

    assert_eq!(StatusCode::OK, response.status_code);
    assert_eq!(
        acct_id,
        get_acct_for_addr(&test_ctx.address_repo, &addr1.address).await
    );
}

#[rstest]
#[tokio::test]
async fn test_register_watch_address_with_different_account_id_second_insert_500(
    addr1: AddressAndKeysetId,
    #[values(memory_repo(), ddb_repo().await)] repo: impl AddressWatchlistTrait + Clone + 'static,
) {
    let test_ctx = TestContext::set_up(repo).await;
    let first_acct_id = test_ctx.known_account_id_1;
    let second_acct_id = test_ctx.known_account_id_2;

    let _ = test_ctx
        .client
        .register_watch_address(&first_acct_id, &vec![addr1.clone()].into())
        .await;

    let response = test_ctx
        .client
        .register_watch_address(&second_acct_id, &vec![addr1.clone()].into())
        .await;

    assert_eq!(StatusCode::INTERNAL_SERVER_ERROR, response.status_code);
}

#[rstest]
#[tokio::test]
async fn test_register_watch_address_unauth_401(
    addr1: AddressAndKeysetId,
    #[values(memory_repo(), ddb_repo().await)] repo: impl AddressWatchlistTrait + Clone + 'static,
) {
    let test_ctx = TestContext::set_up(repo).await;
    let acct_id = test_ctx.known_account_id_1;

    let response = test_ctx
        .client
        .register_watch_address_unauth(&acct_id, &vec![addr1.clone()].into())
        .await;

    assert_eq!(StatusCode::UNAUTHORIZED, response.status_code);
}
