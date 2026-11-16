package app.tennyo.host.engine.pairing

class InMemoryPairStore : PersistentPairStore() {
    private var records = emptyList<RememberedPairRecord>()

    override fun load(): List<RememberedPairRecord> = records

    override fun store(records: List<RememberedPairRecord>) {
        this.records = records
    }
}
