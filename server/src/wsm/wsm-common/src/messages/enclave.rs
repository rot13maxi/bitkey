use bitcoin::util::bip32::{DerivationPath, ExtendedPubKey};
use bitcoin::Network;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Default)]
pub struct KmsRequest {
    pub region: String,
    pub proxy_port: String,
    pub akid: String,
    pub skid: String,
    pub session_token: String,
    pub ciphertext: String,
    pub cmk_id: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct LoadSecretRequest {
    // This is mostly duplicated from KmsRequest above because
    // changing the API of existing endpoints is breaking, due to
    // wsm-api and wsm-enclave not being deployed at the same time.
    // We can change this later.
    pub region: String,
    pub proxy_port: String,
    pub akid: String,
    pub skid: String,
    pub session_token: String,
    pub dek_id: String,
    pub ciphertext: String,
    pub cmk_id: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct LoadIntegrityKeyRequest {
    pub request: KmsRequest,
    pub use_test_key: bool,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct LoadedSecret {
    pub status: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct EnclaveSignRequest {
    pub root_key_id: String,
    pub wrapped_xprv: String,
    pub dek_id: String,
    pub key_nonce: String,
    pub descriptor: String,
    pub change_descriptor: String,
    pub psbt: String,
    pub network: Option<Network>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct EnclaveGenericSignRequest {
    pub root_key_id: String,
    pub wrapped_xprv: String,
    pub dek_id: String,
    pub key_nonce: String,
    pub blob: String,
    pub network: Option<Network>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct EnclaveCreateKeyRequest {
    pub root_key_id: String,
    pub dek_id: String,
    pub network: Network,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct EnclaveDeriveKeyRequest {
    pub key_id: String,
    pub dek_id: String,
    pub wrapped_xprv: String,
    pub key_nonce: String,
    pub derivation_path: DerivationPath,
    pub network: Option<Network>,
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
pub struct CreatedKey {
    pub xpub: ExtendedPubKey,
    pub dpub: String,
    #[serde(default)]
    pub xpub_sig: String,
    pub wrapped_xprv: String,
    pub wrapped_xprv_nonce: String,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(untagged)]
pub enum CreateResponse {
    Single(CreatedKey),
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
pub struct DerivedKey {
    pub xpub: ExtendedPubKey,
    pub dpub: String,
    #[serde(default)]
    pub xpub_sig: String,
}
#[derive(Serialize, Deserialize, Debug)]
pub struct DeriveResponse(pub DerivedKey);

#[derive(Serialize, Deserialize, Debug)]
pub struct EnclaveSignWithIntegrityKeyRequest {
    pub data: String,
    pub dek_id: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct EnclaveSignWithIntegrityKeyResponse {
    pub signature: String,
}
