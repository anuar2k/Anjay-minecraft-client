package li.cil.oc.server.component

import com.avsystem.anjay._
import li.cil.oc.api.Network
import li.cil.oc.api.driver.DeviceInfo
import li.cil.oc.api.driver.DeviceInfo.{DeviceAttribute, DeviceClass}
import li.cil.oc.api.machine.{Arguments, Callback, Context}
import li.cil.oc.api.network.{Node, Visibility}
import li.cil.oc.api.prefab.{AbstractManagedEnvironment, AbstractValue}
import li.cil.oc.{Constants, OpenComputers}
import net.minecraft.nbt.NBTTagCompound
import org.apache.logging.log4j.Level

import java.{lang => jl, util => ju}
import scala.collection.JavaConverters._

// TODO: should Anjay be persisted after wakeup in case the player enters the chunk?
class AnjayCard extends AbstractManagedEnvironment with DeviceInfo {

  import AnjayCard._

  private final lazy val deviceInfo = Map(
    DeviceAttribute.Class -> DeviceClass.Communication,
    DeviceAttribute.Description -> "LwM2M Client",
    DeviceAttribute.Vendor -> Constants.DeviceInfo.AVSystem,
    DeviceAttribute.Product -> "Anjay"
  ).asJava
  override val node: Node = Network.newNode(this, Visibility.Network)
    .withComponent("anjay", Visibility.Neighbors).create()

  override def getDeviceInfo: ju.Map[String, String] = deviceInfo

  @Callback
  def setup(context: Context, args: Arguments): Array[AnyRef] = {
    try {
      return result(configSetup(args.checkTable(0).asInstanceOf[ju.Map[String, AnyRef]]))
    }
    catch {
      case e: Exception =>
        OpenComputers.log.log(Level.ERROR, s"Exception: $e")
        e.printStackTrace()
    }

    result(())
  }

  private def configSetup(config: ju.Map[String, AnyRef]): AnjayValue = {
    val endpointName = config.get("endpoint").asInstanceOf[String]
    val uri = config.get("uri").asInstanceOf[String]

    val anjayConfig = new Anjay.Configuration
    anjayConfig.endpointName = endpointName
    anjayConfig.inBufferSize = 4000
    anjayConfig.outBufferSize = 4000
    anjayConfig.msgCacheSize = 65536

    val anjay = new Anjay(anjayConfig)

    AnjayAttrStorage.install(anjay)

    val serverObjectInstance = new AnjayServerObject.Instance
    serverObjectInstance.ssid = 1
    serverObjectInstance.lifetime = 60
    serverObjectInstance.binding = "U"

    AnjayServerObject.install(anjay).addInstance(serverObjectInstance)

    val securityObjectInstance = new AnjaySecurityObject.Instance
    securityObjectInstance.ssid = 1
    securityObjectInstance.serverUri = ju.Optional.of(uri)

    uri match {
      case coap() =>
        securityObjectInstance.securityMode = AnjaySecurityObject.SecurityMode.NOSEC
      case coaps() =>
        securityObjectInstance.securityMode = AnjaySecurityObject.SecurityMode.PSK
        securityObjectInstance.publicCertOrPskIdentity = ju.Optional.of(endpointName.getBytes)
        securityObjectInstance.privateCertOrPskKey = ju.Optional.of(config.get("psk").asInstanceOf[String].getBytes)
      case _ => throw new IllegalArgumentException("Invalid protocol prefix")
    }

    AnjaySecurityObject.install(anjay).addInstance(securityObjectInstance)

    new AnjayValue(anjay)
  }
}

object AnjayCard {
  private val coap = raw"^coap://".r.unanchored
  private val coaps = raw"^coaps://".r.unanchored

  sealed trait HolderResource

  sealed trait ValueHolderResource extends HolderResource {
    def readValue(context: AnjayOutputContext): Unit

    def writeValue(context: AnjayInputContext): Unit

    def getValue: AnyRef

    def setValue(value: AnyRef): Unit
  }

  class AnjayThread(val anjay: Anjay) extends Thread {
    var loop: Option[AnjayEventLoop] = None

    override def run(): Unit = {
      try {
        this.synchronized {
          loop = Some(new AnjayEventLoop(anjay, 100))
        }
        loop.foreach(_.run())
      }
      finally {
        this.synchronized {
          loop.foreach(_.close())
        }
      }
    }

    def shutdown(): Unit = {
      this.synchronized {
        loop.foreach(_.interrupt())
        loop = None
      }
    }
  }

  class AnjayValue extends AbstractValue {
    var anjayOpt: Option[Anjay] = None
    var anjayThreadOpt: Option[AnjayThread] = None
    var objects: Map[Int, HolderObject] = Map.empty

    def this(anjay: Anjay) = {
      this()
      anjayOpt = Option(anjay)
    }

    @Callback
    def run(context: Context, args: Arguments): Array[AnyRef] = {
      anjayOpt match {
        case Some(anjay) =>
          anjayThreadOpt = Some(new AnjayThread(anjay))
          anjayThreadOpt.foreach(_.start())
        case _ => throw new IllegalStateException("Can't run Anjay without configuring it first")
      }

      result(())
    }

    @Callback
    def addObject(context: Context, args: Arguments): Array[AnyRef] = {
      val oid = args.checkInteger(0);
      val obj = new HolderObject(oid)
      objects += oid -> obj
      anjayOpt.foreach(_.registerObject(obj))
      result(())
    }

    @Callback
    def addInstance(context: Context, args: Arguments): Array[AnyRef] = {
      val oid = args.checkInteger(0)
      val iid = args.checkInteger(1)
      val obj = objects(oid)
      val inst = new HolderInstance(oid, iid)
      obj.addInstance(anjayOpt, inst)
      result(())
    }

    @Callback
    def addResource(context: Context, args: Arguments): Array[AnyRef] = {
      val oid = args.checkInteger(0)
      val iid = args.checkInteger(1)
      val rid = args.checkInteger(2)
      val kind = args.checkString(3)
      val res_type = args.checkString(4)
      val obj = objects(oid)
      val inst = obj.insts(iid)
      val resource = res_type match {
        case "double" => new DoubleHolderResource
        case "int" => new IntHolderResource
        case "boolean" => new BooleanHolderResource
        case "string" => new StringHolderResource
        case _ => kind match {
          case "e" => new ExecutableHolderResource
          case _ => throw new IllegalArgumentException("Invalid resource type")
        }
      }
      inst.addResource(anjayOpt, rid, resource, kind match {
        case "r" => AnjayObject.ResourceKind.R
        case "w" => AnjayObject.ResourceKind.W
        case "rw" => AnjayObject.ResourceKind.RW
        case "e" => AnjayObject.ResourceKind.E
        case _ => throw new IllegalArgumentException("Invalid resource kind")
      })
      result(())
    }

    @Callback
    def getValue(context: Context, args: Arguments): Array[AnyRef] = {
      val oid = args.checkInteger(0)
      val iid = args.checkInteger(1)
      val rid = args.checkInteger(2)

      objects(oid).insts(iid).resources(rid)._2 match {
        case resource: ValueHolderResource => result(resource.getValue)
        case _ => throw new IllegalArgumentException("Non-value resource")
      }
    }

    @Callback
    def setValue(context: Context, args: Arguments): Array[AnyRef] = {
      val oid = args.checkInteger(0)
      val iid = args.checkInteger(1)
      val rid = args.checkInteger(2)
      val value = args.checkAny(3)

      val (definition, resource) = objects(oid).insts(iid).resources(rid)
      if (definition.kind.equals(AnjayObject.ResourceKind.W)) throw new IllegalArgumentException("Writable-only resource")
      resource match {
        case resource: ValueHolderResource =>
          value match {
            case array: Array[Byte] =>
              resource.setValue(new String(array))
            case _ =>
              resource.setValue(value)
          }
          anjayOpt.foreach(_.notifyChanged(oid, iid, rid))
          result(())
        case _ => throw new IllegalArgumentException("Non-value resource")
      }
    }

    @Callback
    def shutdown(context: Context, args: Arguments): Array[AnyRef] = {
      shutdown()
      result(())
    }

    def shutdown(): Unit = {
      anjayThreadOpt.foreach(_.shutdown())
      anjayOpt.foreach(_.close())
    }

    override def dispose(context: Context): Unit = {
      shutdown()
      OpenComputers.log.log(Level.INFO, "AnjayValue got disposed!")
    }

    override def load(nbt: NBTTagCompound): Unit = {
      OpenComputers.log.log(Level.INFO, "AnjayValue got loaded!")
    }

    override def save(nbt: NBTTagCompound): Unit = {
      OpenComputers.log.log(Level.INFO, "AnjayValue got loaded!")
    }
  }

  class HolderObject(oid: Int) extends AnjayObject {
    var insts: Map[Int, HolderInstance] = Map.empty

    def addInstance(anjayOpt: Option[Anjay], instance: HolderInstance): Unit = {
      insts += instance.iid -> instance
      anjayOpt.foreach(_.notifyInstancesChanged(oid))
    }

    override def oid(): Int = oid

    override def instances(): ju.SortedSet[Integer] = new ju.TreeSet(insts.keys.map(new Integer(_)).asJavaCollection)

    override def resources(iid: Int): ju.SortedSet[AnjayObject.ResourceDef] = new ju.TreeSet(insts(iid)
      .resources.values.map(_._1).asJavaCollection)

    override def resourceRead(iid: Int, rid: Int, context: AnjayOutputContext): Unit =
      insts(iid).resourceRead(rid, context)

    override def resourceWrite(iid: Int, rid: Int, context: AnjayInputContext): Unit =
      insts(iid).resourceWrite(rid, context)

    override def resourceExecute(iid: Int, rid: Int, args: ju.Map[Integer, ju.Optional[String]]): Unit = {
      if (!args.isEmpty) {
        throw new IllegalArgumentException("Execute arguments are not supported")
      }
      insts(iid).resourceExecute(rid)
    }

    override def transactionBegin(): Unit = ()

    override def transactionValidate(): Unit = ()

    override def transactionRollback(): Unit = ()

    override def transactionCommit(): Unit = ()
  }

  class HolderInstance(val oid: Int, val iid: Int) {
    var resources: Map[Int, (AnjayObject.ResourceDef, HolderResource)] = Map.empty

    def addResource(anjayOpt: Option[Anjay], rid: Int, resource: HolderResource, kind: AnjayObject.ResourceKind): Unit = {
      resources += rid -> (new AnjayObject.ResourceDef(rid, kind, true), resource)
      anjayOpt.foreach(_.notifyChanged(oid, iid, rid))
    }

    def resourceRead(rid: Int, context: AnjayOutputContext): Unit = resources(rid) match {
      case (_, resource: ValueHolderResource) => resource.readValue(context)
      case _ => throw new IllegalArgumentException("Non-value resource")
    }

    def resourceWrite(rid: Int, context: AnjayInputContext): Unit = resources(rid) match {
      case (_, resource: ValueHolderResource) => resource.writeValue(context)
      case _ => throw new IllegalArgumentException("Non-value resource")
    }

    def resourceExecute(rid: Int): Unit = resources(rid) match {
      case (_, resource: ExecutableHolderResource) => resource.incrementCount()
      case _ => throw new IllegalArgumentException("Non-executable resource")
    }
  }

  class ExecutableHolderResource extends HolderResource {
    var executionCount = 0

    def incrementCount(): Unit = this.synchronized {
      executionCount += 1
    }

    def getCount: Int = this.synchronized {
      executionCount
    }
  }

  class DoubleHolderResource extends ValueHolderResource {
    var value: jl.Double = 0

    override def readValue(context: AnjayOutputContext): Unit = this.synchronized {
      context.retDouble(value)
    }

    override def writeValue(context: AnjayInputContext): Unit = this.synchronized {
      value = context.getDouble
    }

    override def getValue: AnyRef = this.synchronized {
      value
    }

    override def setValue(value: AnyRef): Unit = this.synchronized {
      value match {
        case n: jl.Number => this.value = n.doubleValue
      }
    }
  }

  class IntHolderResource extends ValueHolderResource {
    var value: jl.Integer = 0

    override def readValue(context: AnjayOutputContext): Unit = this.synchronized {
      context.retInt(value)
    }

    override def writeValue(context: AnjayInputContext): Unit = this.synchronized {
      value = context.getInt
    }

    override def getValue: AnyRef = this.synchronized {
      value
    }

    override def setValue(value: AnyRef): Unit = this.synchronized {
      value match {
        case n: jl.Number => this.value = n.intValue
      }
    }
  }

  class BooleanHolderResource extends ValueHolderResource {
    var value: jl.Boolean = false

    override def readValue(context: AnjayOutputContext): Unit = this.synchronized {
      context.retBoolean(value)
    }

    override def writeValue(context: AnjayInputContext): Unit = value = this.synchronized {
      context.getBoolean
    }

    override def getValue: AnyRef = this.synchronized {
      value
    }

    override def setValue(value: AnyRef): Unit = this.synchronized {
      value match {
        case b: jl.Boolean => this.value = b
      }
    }
  }

  class StringHolderResource extends ValueHolderResource {
    var value: String = ""

    override def readValue(context: AnjayOutputContext): Unit = this.synchronized {
      context.retString(value)
    }

    override def writeValue(context: AnjayInputContext): Unit = this.synchronized {
      value = context.getString
    }

    override def getValue: AnyRef = this.synchronized {
      value
    }

    override def setValue(value: AnyRef): Unit = this.synchronized {
      value match {
        case s: String => this.value = s
      }
    }
  }
}
