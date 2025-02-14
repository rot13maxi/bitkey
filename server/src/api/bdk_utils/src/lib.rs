use std::collections::BTreeMap;
use std::str::FromStr;
use std::{env, fmt};

pub use bdk;
use bdk::bitcoin::bip32::{ChildNumber, KeySource};
use bdk::bitcoin::psbt::PartiallySignedTransaction;
use bdk::bitcoin::psbt::Psbt;
use bdk::bitcoin::secp256k1::PublicKey;
use bdk::bitcoin::ScriptBuf;
use bdk::blockchain::{Blockchain, ElectrumBlockchain};
use bdk::database::{AnyDatabase, BatchDatabase};
use bdk::descriptor::ExtendedDescriptor;
use bdk::electrum_client::Client as ElectrumClient;
use bdk::electrum_client::Config as ElectrumConfig;
use bdk::miniscript::descriptor::DescriptorXKey;
use bdk::miniscript::{Descriptor, DescriptorPublicKey};
use bdk::wallet::AddressIndex;
use bdk::SyncOptions;
use bdk::{bitcoin::Network, database::MemoryDatabase, Wallet};
use feature_flags::service::Service as FeatureFlagsService;
use flags::{
    FLAG_MAINNET_ELECTRUM_RPC_URI, FLAG_SIGNET_ELECTRUM_RPC_URI, FLAG_TESTNET_ELECTRUM_RPC_URI,
};
use tracing::{event, instrument, Level};

use error::BdkUtilError;
use errors::ApiError;
use url::Url;

pub mod constants;
pub mod error;
pub mod flags;
pub mod metrics;
pub mod serde;
pub mod signature;

pub trait TransactionBroadcasterTrait: Send + Sync {
    fn broadcast(
        &self,
        wallet: Wallet<AnyDatabase>,
        transaction: &mut PartiallySignedTransaction,
        rpc_uris: &ElectrumRpcUris,
    ) -> Result<(), ApiError>;
}

impl fmt::Debug for dyn TransactionBroadcasterTrait {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Trait object of TransactionBroadcasterTrait")
    }
}

pub struct TransactionBroadcaster;

impl TransactionBroadcasterTrait for TransactionBroadcaster {
    #[instrument(skip(self, wallet, transaction))]
    fn broadcast(
        &self,
        wallet: Wallet<AnyDatabase>,
        transaction: &mut PartiallySignedTransaction,
        rpc_uris: &ElectrumRpcUris,
    ) -> Result<(), ApiError> {
        let network = wallet.network();
        let blockchain = get_blockchain(network, rpc_uris)?;
        blockchain
            .broadcast(&transaction.to_owned().extract_tx())
            .map_err(|err| {
                event!(
                    Level::ERROR,
                    "Failed to broadcast PSBT: {}",
                    err.to_string()
                );
                ApiError::GenericInternalApplicationError("Failed to broadcast PSBT".to_string())
            })?;
        Ok(())
    }
}

pub fn get_electrum_client(
    network: Network,
    rpc_uris: &ElectrumRpcUris,
) -> Result<ElectrumClient, BdkUtilError> {
    let electrum_config = get_electrum_server(network, rpc_uris)?;
    let config = ElectrumConfig::builder().timeout(Some(5)).build();
    Ok(ElectrumClient::from_config(
        &electrum_config.to_url(),
        config,
    )?)
}

pub fn get_blockchain(
    network: Network,
    rpc_uris: &ElectrumRpcUris,
) -> Result<ElectrumBlockchain, BdkUtilError> {
    Ok(ElectrumBlockchain::from(get_electrum_client(
        network, rpc_uris,
    )?))
}

#[derive(Clone)]
pub struct ElectrumServerConfig {
    pub scheme: String,
    pub host: String,
    pub port: u16,
}

impl ElectrumServerConfig {
    fn to_url(&self) -> String {
        format!("{}://{}:{}", self.scheme, self.host, self.port)
    }
}

#[derive(Debug)]
pub struct ElectrumRpcUris {
    pub mainnet: String,
    pub testnet: String,
    pub signet: String,
}

pub fn generate_electrum_rpc_uris(
    service: &FeatureFlagsService,
) -> Result<ElectrumRpcUris, ApiError> {
    //TODO: [W-4850] Move to axum extractor
    let mainnet = FLAG_MAINNET_ELECTRUM_RPC_URI.resolver(service).resolve();
    let testnet = FLAG_TESTNET_ELECTRUM_RPC_URI.resolver(service).resolve();
    let signet = FLAG_SIGNET_ELECTRUM_RPC_URI.resolver(service).resolve();

    Ok(ElectrumRpcUris {
        mainnet,
        testnet,
        signet,
    })
}

pub fn get_electrum_server(
    network: Network,
    rpc_uris: &ElectrumRpcUris,
) -> Result<ElectrumServerConfig, BdkUtilError> {
    match network {
        Network::Bitcoin => parse_electrum_server(&rpc_uris.mainnet),
        Network::Testnet => parse_electrum_server(&rpc_uris.testnet),
        Network::Signet => parse_electrum_server(&rpc_uris.signet),
        Network::Regtest => {
            let server = env::var("REGTEST_ELECTRUM_SERVER_URI")
                .map_err(|_| BdkUtilError::UnsupportedBitcoinNetwork(network.to_string()))?;
            parse_electrum_server(&server)
        }
        _ => Err(BdkUtilError::UnsupportedBitcoinNetwork(network.to_string())),
    }
}

pub fn parse_electrum_server(server: &str) -> Result<ElectrumServerConfig, BdkUtilError> {
    let url = Url::parse(server).map_err(|_| BdkUtilError::MalformedURI)?;
    let host = url.host().ok_or(BdkUtilError::MalformedURI)?.to_string();
    let port = url.port().ok_or(BdkUtilError::MalformedURI)?;

    Ok(ElectrumServerConfig {
        scheme: url.scheme().to_string(),
        host,
        port,
    })
}

#[instrument(name = "sync_wallet", fields(network), skip(wallet))]
pub fn sync_wallet<D: BatchDatabase>(
    wallet: &Wallet<D>,
    rpc_uris: &ElectrumRpcUris,
) -> Result<(), BdkUtilError> {
    let network = wallet.network();
    let blockchain = get_blockchain(network, rpc_uris)?;
    wallet
        .sync(&blockchain, SyncOptions::default())
        .map_err(BdkUtilError::WalletSync)
}

pub fn validate_xpubs(xpubs: &[String]) -> Result<(), ApiError> {
    if xpubs
        .iter()
        .map(|xpub_str| DescriptorPublicKey::from_str(xpub_str))
        .all(|r| r.is_ok())
    {
        Ok(())
    } else {
        Err(ApiError::GenericBadRequest(
            "Invalid input: One or both of the XPubs is invalid".to_string(),
        ))
    }
}

const RECEIVING_PATH: [ChildNumber; 1] = [ChildNumber::Normal { index: 0 }];
const CHANGE_PATH: [ChildNumber; 1] = [ChildNumber::Normal { index: 1 }];

pub struct DescriptorKeyset {
    network: Network,
    app: DescriptorPublicKey,
    hw: DescriptorPublicKey,
    server: DescriptorPublicKey,
}

impl DescriptorKeyset {
    pub fn new(
        network: Network,
        app: DescriptorPublicKey,
        hw: DescriptorPublicKey,
        server: DescriptorPublicKey,
    ) -> Self {
        Self {
            network,
            app,
            hw,
            server,
        }
    }

    pub fn receiving(&self) -> DescriptorKeyset {
        self.derive(&RECEIVING_PATH)
    }
    pub fn change(&self) -> DescriptorKeyset {
        self.derive(&CHANGE_PATH)
    }

    fn derive(&self, path: &[ChildNumber]) -> DescriptorKeyset {
        DescriptorKeyset {
            network: self.network,
            app: extend_descriptor_public_key(&self.app, path),
            hw: extend_descriptor_public_key(&self.hw, path),
            server: extend_descriptor_public_key(&self.server, path),
        }
    }

    pub fn into_multisig_descriptor(self) -> Result<ExtendedDescriptor, BdkUtilError> {
        Descriptor::<DescriptorPublicKey>::new_wsh_sortedmulti(
            2,
            vec![self.app, self.hw, self.server],
        )
        .map_err(BdkUtilError::GenerateDescriptorForDescriptorKeyset)
    }

    pub fn generate_wallet(
        &self,
        sync: bool,
        rpc_uris: &ElectrumRpcUris,
    ) -> Result<Wallet<AnyDatabase>, BdkUtilError> {
        let receiving = self.receiving().into_multisig_descriptor()?;
        let change = self.change().into_multisig_descriptor()?;

        let wallet = Wallet::new(
            receiving,
            Some(change),
            self.network,
            MemoryDatabase::new().into(),
        )
        .map_err(BdkUtilError::GenerateWalletForDescriptorKeyset)?;

        if sync {
            sync_wallet(&wallet, rpc_uris)?;
        }

        Ok(wallet)
    }
}

pub fn is_psbt_addressed_to_wallet(
    wallet: &Wallet<AnyDatabase>,
    psbt: &Psbt,
) -> Result<bool, BdkUtilError> {
    wallet
        .ensure_addresses_cached(100)
        .map_err(BdkUtilError::WalletCacheAddresses)?;

    for tx_out in &psbt.unsigned_tx.output {
        if !wallet
            .is_mine(&tx_out.script_pubkey)
            .map_err(BdkUtilError::PsbtNotAddressedToAWallet)?
        {
            return Ok(false);
        }
    }

    Ok(true)
}

fn extend_descriptor_public_key(
    origin: &DescriptorPublicKey,
    path: &[ChildNumber],
) -> DescriptorPublicKey {
    match origin {
        DescriptorPublicKey::XPub(xpub) => DescriptorPublicKey::XPub(DescriptorXKey {
            derivation_path: xpub.derivation_path.extend(path),
            origin: xpub.origin.clone(),
            ..*xpub
        }),
        // TODO [W-5549] Don't panic when extending invalid DescriptorPublicKey variant
        _ => unimplemented!(),
    }
}

pub trait AttributableWallet {
    fn is_addressed_to_self(&self, psbt: &Psbt) -> Result<bool, BdkUtilError>;
    fn all_inputs_are_from_self(&self, psbt: &Psbt) -> Result<bool, BdkUtilError>;
    fn is_my_psbt_address(&self, spk: &SpkWithDerivationPaths) -> Result<bool, BdkUtilError>;
}

impl<D> AttributableWallet for Wallet<D>
where
    D: BatchDatabase,
{
    fn is_addressed_to_self(&self, psbt: &Psbt) -> Result<bool, BdkUtilError> {
        if psbt.outputs.is_empty() {
            return Ok(false);
        }
        for output_spk in psbt
            .get_all_outputs_as_spk_and_derivation()
            .ok_or(BdkUtilError::MalformedDerivationPath)?
        {
            if !self.is_my_psbt_address(&output_spk)? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn all_inputs_are_from_self(&self, psbt: &Psbt) -> Result<bool, BdkUtilError> {
        for input_spk in psbt
            .get_all_inputs_as_spk_and_derivation()
            .ok_or(BdkUtilError::MissingWitnessUtxo)?
        {
            if !self.is_my_psbt_address(&input_spk)? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn is_my_psbt_address(&self, spk: &SpkWithDerivationPaths) -> Result<bool, BdkUtilError> {
        if let Some(first_entry) = spk.derivation_paths.first_key_value() {
            let first_derivation_path = first_entry.1.clone().1;
            let path_components: Vec<ChildNumber> = first_derivation_path.into();
            // all of our derivation paths have two unhardened components at the end ../[change]/index
            let index = match path_components
                .iter()
                .nth_back(0)
                .ok_or(BdkUtilError::MalformedDerivationPath)?
            {
                ChildNumber::Normal { index } => index,
                ChildNumber::Hardened { .. } => return Err(BdkUtilError::MalformedDerivationPath),
            };
            let is_change = match path_components
                .iter()
                .nth_back(1)
                .ok_or(BdkUtilError::MalformedDerivationPath)?
            {
                ChildNumber::Normal { index } => *index == 1,
                ChildNumber::Hardened { .. } => return Err(BdkUtilError::MalformedDerivationPath),
            };

            let derived_address = match is_change {
                true => self.get_internal_address(AddressIndex::Peek(*index)),
                false => self.get_address(AddressIndex::Peek(*index)),
            }
            .map_err(BdkUtilError::WalletCacheAddresses)?;
            Ok(derived_address.address.script_pubkey() == spk.script_pubkey.clone())
        } else {
            Ok(false)
        }
    }
}
/// Helper type that contains a scriptpubkey (address) along with any derivation paths associated with it
/// It is intended to be used when pulling inputs or outputs from a PSBT and checking if they are owned
/// by a particular wallet.
pub struct SpkWithDerivationPaths {
    pub script_pubkey: ScriptBuf,
    pub derivation_paths: BTreeMap<PublicKey, KeySource>,
}

/// PSBTs contain origin information and scriptpubkeys for inputs and outputs that are owned by our wallet.
/// PsbtWithDerivation is a helper trait that makes it easier to get all that information for checking ownership.
/// Outputs that are not owned by our wallet will be missing derivation paths.
/// Likewise, the scriptpubkey on an input comes from the witness_utxo, which is only populated if the
/// wallet had the UTXO information at construction-time. So all of these methods return Options.
pub trait PsbtWithDerivation {
    fn get_input_spk_and_derivation(&self, idx: usize) -> Option<SpkWithDerivationPaths>;
    fn get_all_inputs_as_spk_and_derivation(&self) -> Option<Vec<SpkWithDerivationPaths>>;
    fn get_output_spk_and_derivation(&self, idx: usize) -> Option<SpkWithDerivationPaths>;

    fn get_all_outputs_as_spk_and_derivation(&self) -> Option<Vec<SpkWithDerivationPaths>>;
}

impl PsbtWithDerivation for Psbt {
    fn get_input_spk_and_derivation(&self, idx: usize) -> Option<SpkWithDerivationPaths> {
        let input = self.inputs.get(idx)?;
        Some(SpkWithDerivationPaths {
            script_pubkey: input.witness_utxo.clone()?.script_pubkey,
            derivation_paths: input.bip32_derivation.clone(),
        })
    }

    fn get_all_inputs_as_spk_and_derivation(&self) -> Option<Vec<SpkWithDerivationPaths>> {
        let mut res = Vec::new();
        for (idx, _) in self.inputs.iter().enumerate() {
            res.push(self.get_input_spk_and_derivation(idx)?);
        }
        Some(res)
    }

    fn get_output_spk_and_derivation(&self, idx: usize) -> Option<SpkWithDerivationPaths> {
        let txout = self.unsigned_tx.output.get(idx)?;
        let output = self.outputs.get(idx)?;
        Some(SpkWithDerivationPaths {
            script_pubkey: txout.script_pubkey.clone(),
            derivation_paths: output.bip32_derivation.clone(),
        })
    }

    fn get_all_outputs_as_spk_and_derivation(&self) -> Option<Vec<SpkWithDerivationPaths>> {
        let mut res = Vec::new();
        for (idx, _) in self.outputs.iter().enumerate() {
            res.push(self.get_output_spk_and_derivation(idx)?);
        }
        Some(res)
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use bdk::bitcoin::bip32::ExtendedPrivKey;
    use bdk::bitcoin::secp256k1::Secp256k1;
    use bdk::bitcoin::Network;
    use bdk::database::{AnyDatabase, BatchOperations, MemoryDatabase};
    use bdk::descriptor::IntoWalletDescriptor;
    use bdk::keys::GeneratableKey;
    use bdk::template::{Bip84, DescriptorTemplate};
    use bdk::wallet::tx_builder::TxOrdering;
    use bdk::wallet::AddressIndex;
    use bdk::BlockTime;
    use bdk::{bitcoin, populate_test_db, testutils, KeychainKind, Wallet};

    use crate::{get_electrum_server, AttributableWallet, ElectrumRpcUris, PsbtWithDerivation};

    fn get_test_wallet() -> Wallet<AnyDatabase> {
        let xprv = ExtendedPrivKey::generate(()).unwrap();

        let funding_address_kix = 0;
        let external_descriptor = Bip84(xprv.clone(), KeychainKind::External)
            .build(Network::Signet)
            .unwrap()
            .into_wallet_descriptor(&Secp256k1::new(), Network::Signet)
            .unwrap()
            .0
            .to_string();

        // pre-populate the wallet database with a fake transaction
        let descriptors = testutils!(@descriptors (&external_descriptor));
        let tx_meta = testutils! {
                @tx ( (@external descriptors, funding_address_kix) => 50_000 ) (@confirmations 1)
        };
        let mut wallet_database = MemoryDatabase::new();
        wallet_database
            .set_script_pubkey(
                &bitcoin::Address::from_str(&tx_meta.output.first().unwrap().to_address)
                    .unwrap()
                    .assume_checked()
                    .script_pubkey(),
                KeychainKind::External,
                funding_address_kix,
            )
            .unwrap();
        wallet_database
            .set_last_index(KeychainKind::External, funding_address_kix)
            .unwrap();
        populate_test_db!(&mut wallet_database, tx_meta, Some(100));

        Wallet::new(
            Bip84(xprv.clone(), KeychainKind::External),
            Some(Bip84(xprv, KeychainKind::Internal)),
            Network::Signet,
            AnyDatabase::Memory(wallet_database),
        )
        .unwrap()
    }

    #[test]
    fn test_psbt_input_validation_works() {
        let wallet = get_test_wallet();
        let mut builder = wallet.build_tx();
        builder.add_recipient(
            wallet
                .get_address(AddressIndex::New)
                .unwrap()
                .script_pubkey(),
            1000,
        );
        let (psbt, _) = builder.finish().unwrap();
        assert_eq!(psbt.inputs.len(), 1); // make sure we have an input
                                          // check the individial input
        assert!(wallet
            .is_my_psbt_address(&psbt.get_input_spk_and_derivation(0).unwrap())
            .unwrap());

        // check the easy way
        assert!(wallet.all_inputs_are_from_self(&psbt).unwrap());
    }

    #[test]
    fn test_psbt_output_validation_works() {
        let wallet = get_test_wallet();
        let recipient = get_test_wallet();
        let mut builder = wallet.build_tx();
        builder.ordering(TxOrdering::Untouched); // We are going to be looking at specific outputs, so don't reshuffle the ordering
                                                 // coins to self
        builder.add_recipient(
            wallet
                .get_address(AddressIndex::New)
                .unwrap()
                .script_pubkey(),
            1000,
        );
        // coins to another wallet
        builder.add_recipient(
            recipient
                .get_address(AddressIndex::New)
                .unwrap()
                .script_pubkey(),
            1000,
        );
        let (psbt, _) = builder.finish().unwrap();
        assert_eq!(psbt.inputs.len(), 1); // make sure we have an input
        assert_eq!(psbt.outputs.len(), 3); // we should have one output for our self-spend, one for the recipient, one for change

        assert!(!wallet.is_addressed_to_self(&psbt).unwrap());

        assert!(wallet
            .is_my_psbt_address(&psbt.get_output_spk_and_derivation(0).unwrap())
            .is_ok_and(|_| true));
        assert!(wallet
            .is_my_psbt_address(&psbt.get_output_spk_and_derivation(1).unwrap())
            .is_ok_and(|result| !result));
    }

    #[test]
    fn test_checking_self_spends_works() {
        let wallet = get_test_wallet();
        let mut builder = wallet.build_tx();
        // coins to self
        builder.add_recipient(
            wallet
                .get_address(AddressIndex::New)
                .unwrap()
                .script_pubkey(),
            1000,
        );
        let (psbt, _) = builder.finish().unwrap();
        assert_eq!(psbt.inputs.len(), 1); // make sure we have an input
        assert_eq!(psbt.outputs.len(), 2); // we should have one output for our self-spend, one for change
        assert!(wallet.is_addressed_to_self(&psbt).unwrap());
    }

    #[test]
    fn test_get_electrum_server() {
        let rpc_uris = ElectrumRpcUris {
            mainnet: "ssl://testelectrumserver1.wallet.build:50002".to_string(),
            testnet: "ssl://testelectrumserver2.wallet.build:50002".to_string(),
            signet: "ssl://testelectrumserver3.wallet.build:50002".to_string(),
        };
        assert!(get_electrum_server(Network::Bitcoin, &rpc_uris).is_ok());
        assert!(get_electrum_server(Network::Testnet, &rpc_uris).is_ok());
        assert!(get_electrum_server(Network::Signet, &rpc_uris).is_ok());
        assert!(get_electrum_server(Network::Regtest, &rpc_uris).is_err());
    }
}
