package li.cil.oc.integration.opencomputers
import li.cil.oc.api.driver.EnvironmentProvider
import li.cil.oc.api.network.{EnvironmentHost}
import li.cil.oc.common.{Slot, Tier}
import li.cil.oc.server.component
import net.minecraft.item.ItemStack
import li.cil.oc.{Constants, api}

object DriverAnjayCard extends Item {
  override def worksWith(stack: ItemStack): Boolean =
    isOneOf(stack, api.Items.get(Constants.ItemName.AnjayCard))

  override def createEnvironment(stack: ItemStack, host: EnvironmentHost) =
    if (host.world != null && host.world.isRemote) null
    else new component.AnjayCard()

  override def slot(stack: ItemStack) = Slot.Card

  override def tier(stack: ItemStack) = Tier.Two

  object Provider extends EnvironmentProvider {
    override def getEnvironment(stack: ItemStack): Class[_] =
      if (worksWith(stack))
        classOf[component.AnjayCard]
      else null
  }
}
