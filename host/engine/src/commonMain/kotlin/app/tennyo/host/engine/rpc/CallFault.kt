package app.tennyo.host.engine.rpc

import app.tennyo.contract.LinkFaultCode

/** A call that cannot be carried out; the channel answers it with a [app.tennyo.contract.LinkFrame.Fault]. */
class CallFault(val code: LinkFaultCode, override val message: String) : RuntimeException(message)
