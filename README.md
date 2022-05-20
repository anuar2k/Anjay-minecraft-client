# Anjay-minecraft-client

This is a fork of OpenComputers mod for Minecraft that integrates AVSystem's
LwM2M client, Anjay as a scriptable component to use ingame.

## License / Use in Modpacks
This mod is [licensed under the **MIT license**](https://github.com/MightyPirates/OpenComputers/blob/master-MC1.7.10/LICENSE). All **assets are public domain**, unless otherwise stated; all are free to be distributed as long as the license / source credits are kept. This means you can use this mod in any mod pack **as you please**. I'd be happy to hear about you using it, though, just out of curiosity.

## Running the LwM2M client
you'll be probably better off reading the added sources, but...

- create a world, perhaps in Creative Mode
- spawn a OpenComputers computer, you can use `/oc_sc` command for convenience
- insert Anjay LwM2M Client card into the computer (or any other needed cards, e.g. Redstone card)
- start the computer and install the OS as the user is instructed
- write a script that uses `anjay` component as you would do in OC, see the example below

## Example OpenComputers (Lua) program

```lua
-- this script reads a value reported by Daylight Sensor
-- and streams it over LwM2M as Illuminance LwM2M IPSO Object

local component = require("component")
local sides = require("sides")

local anjay = component.anjay
local redstone = component.redstone

client_config = {
   endpoint = "daylight-sensor",
   uri = "coaps://eu.iot.avsystem.cloud",
   psk = "password"
}

client = anjay.setup(client_config)

-- Device Object
client.addObject(3)
client.addInstance(3, 0)
client.addResource(3, 0, 0, "r", "string")
client.addResource(3, 0, 1, "r", "string")

client.setValue(3, 0, 0, "AVSystem")
client.setValue(3, 0, 1, "Daylight Sensor")

-- IPSO Illuminance Object
client.addObject(3301)
client.addInstance(3301, 0)
client.addResource(3301, 0, 5700, "r", "double")

client.run()
print("Running LwM2M client!")

while true do
  light_level = redstone.getInput(sides.left)
  client.setValue(3301, 0, 5700, light_level)
  print("iter done")
  os.sleep(0.25)
end
```

## Caveats

- This was only written as an experiment! The code wasn't written with
  sanitizing arguments passed by the user at all. You
- The JVM crashes when one tries to stop the LwM2M client with `client.shutdown()` Lua call.
- Only Linux x64 hosts are supported natively. If you wish to try running the
  mod on other OS, rebuild Anjay-java library from the fork [you'll find here](https://github.com/anuar2k/anjay-java)
- To run the game you'll need IntelliJ IDEA and follow the instructions from
  the original mod, see here: https://ocdoc.cil.li/tutorial:debug_1.7.10
- JVM needs to be instructed where to look for .so file with native part of
  Anjay-java library, to do so, in IntelliJ run configurations add a VM
  argument like `-Djava.library.path=<path to libs directory of this repo> -Dorg.gradle.jvmargs=-Djava.library.path=<path to libs directory of this repo>`
