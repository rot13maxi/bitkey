import BitcoinDevKit
import Shared

class BdkBlockchainImpl : BdkBlockchain {
    
    let ffiBlockchain: Blockchain
    
    init(ffiBlockchain: Blockchain) {
        self.ffiBlockchain = ffiBlockchain
    }
    
    func broadcastBlocking(transaction: BdkTransaction) -> BdkResult<KotlinUnit> {
        return BdkResult {
            let realTransaction = transaction as! BdkTransactionImpl
            try ffiBlockchain.broadcast(transaction: realTransaction.ffiTransaction)
            return KotlinUnit()
        }
    }
    
    func getBlockHashBlocking(height: Int64) -> BdkResult<NSString> {
        return BdkResult {
            try ffiBlockchain.getBlockHash(height: UInt32(height)) as NSString
        }
    }
    
    func getHeightBlocking() -> BdkResult<KotlinLong> {
        return BdkResult {
            KotlinLong(value: Int64(try ffiBlockchain.getHeight()))
        }
    }
    
    func estimateFee(targetBlocks: UInt64) -> BdkResult<KotlinFloat> {
        return BdkResult {
            KotlinFloat(value: try ffiBlockchain.estimateFee(target: targetBlocks).asSatPerVb())
        }
    }
    
}
