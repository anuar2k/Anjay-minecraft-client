package li.cil.oc.server.component

import java.io._

import li.cil.oc.api.Network
import li.cil.oc.api.driver.EnvironmentHost
import li.cil.oc.api.machine.Arguments
import li.cil.oc.api.machine.Callback
import li.cil.oc.api.machine.Context
import li.cil.oc.api.network._
import li.cil.oc.Settings
import li.cil.oc.api
import net.minecraft.nbt.NBTTagCompound

import scala.language.implicitConversions

class WirelessNetworkCard(val host: EnvironmentHost) extends NetworkCard with WirelessEndpoint {
  override val node = Network.newNode(this, Visibility.Network).
    withComponent("modem", Visibility.Neighbors).
    withConnector().
    create()

  var strength = Settings.get.maxWirelessRange

  // ----------------------------------------------------------------------- //

  override def x = math.floor(host.xPosition).toInt

  override def y = math.floor(host.yPosition).toInt

  override def z = math.floor(host.zPosition).toInt

  override def world = host.world

  // ----------------------------------------------------------------------- //

  @Callback(direct = true, doc = """function():number -- Get the signal strength (range) used when sending messages.""")
  def getStrength(context: Context, args: Arguments): Array[AnyRef] = result(strength)

  @Callback(doc = """function(strength:number):number -- Set the signal strength (range) used when sending messages.""")
  def setStrength(context: Context, args: Arguments): Array[AnyRef] = {
    strength = math.max(args.checkDouble(0), math.min(0, Settings.get.maxWirelessRange))
    result(strength)
  }

  override def isWireless(context: Context, args: Arguments): Array[AnyRef] = result(true)

  override protected def doSend(packet: Packet) {
    if (strength > 0) {
      checkPower()
      api.Network.sendWirelessPacket(this, strength, packet)
    }
    super.doSend(packet)
  }

  override protected def doBroadcast(packet: Packet) {
    if (strength > 0) {
      checkPower()
      api.Network.sendWirelessPacket(this, strength, packet)
    }
    super.doBroadcast(packet)
  }

  private def checkPower() {
    val cost = Settings.get.wirelessCostPerRange
    if (cost > 0 && !Settings.get.ignorePower) {
      if (!node.tryChangeBuffer(-strength * cost)) {
        throw new IOException("not enough energy")
      }
    }
  }

  // ----------------------------------------------------------------------- //

  override val canUpdate = true

  override def update() {
    super.update()
    if (world.getTotalWorldTime % 20 == 0) {
      api.Network.updateWirelessNetwork(this)
    }
  }

  override def onConnect(node: Node) {
    super.onConnect(node)
    if (node == this.node) {
      api.Network.joinWirelessNetwork(this)
    }
  }

  override def onDisconnect(node: Node) {
    super.onDisconnect(node)
    if (node == this.node) {
      api.Network.leaveWirelessNetwork(this)
    }
  }

  // ----------------------------------------------------------------------- //

  override def load(nbt: NBTTagCompound) {
    super.load(nbt)
    if (nbt.hasKey("strength")) {
      strength = nbt.getDouble("strength") max 0 min Settings.get.maxWirelessRange
    }
  }

  override def save(nbt: NBTTagCompound) {
    super.save(nbt)
    nbt.setDouble("strength", strength)
  }
}