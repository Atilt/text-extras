/*
 * This file is part of text-extras, licensed under the MIT License.
 *
 * Copyright (c) 2018 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.kyori.text.adapter.bukkit;

import com.google.gson.JsonDeserializer;
import net.kyori.text.Component;
import net.kyori.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class CraftBukkitAdapter implements Adapter {
  private static final Binding REFLECTION_BINDINGS = load();
  private static final boolean ALIVE = REFLECTION_BINDINGS.valid();

  private static Binding load() {
    try {
      final Class<?> server = Bukkit.getServer().getClass();
      if(!isCompatibleServer(server)) {
        throw new UnsupportedOperationException("Incompatible server version");
      }
      final String serverVersion = maybeVersion(server.getPackage().getName().substring("org.bukkit.craftbukkit".length()));
      final Class<?> craftPlayerClass = craftBukkitClass(serverVersion, "entity.CraftPlayer");
      final Class<?> entityPlayerClass =  craftPlayerClass.getMethod("getHandle").getReturnType();
      final Field playerConnectionField = entityPlayerClass.getField("playerConnection");
      final Class<?> playerConnectionClass = playerConnectionField.getType();
      final Class<?> packetClass = minecraftClass(serverVersion, "Packet");
      final Class<?> baseComponentClass = minecraftClass(serverVersion, "IChatBaseComponent");
      final Class<?> chatPacketClass = minecraftClass(serverVersion, "PacketPlayOutChat");
      final Constructor<?> chatPacketConstructor = chatPacketClass.getConstructor(baseComponentClass);
      final Class<?> titlePacketClass = optionalMinecraftClass(serverVersion, "PacketPlayOutTitle");
      final Class<? extends Enum> titlePacketClassAction;
      final Constructor<?> titlePacketConstructor;

      MethodHandles.Lookup lookup = MethodHandles.lookup();
      final Function<Player, Object> playerHandle = (Function<Player, Object>) LambdaMetafactory.metafactory(lookup,
                "apply",
                MethodType.methodType(Function.class),
                MethodType.methodType(Object.class, Object.class),
                lookup.findVirtual(craftPlayerClass, "getHandle", MethodType.methodType(entityPlayerClass)),
                MethodType.methodType(entityPlayerClass, craftPlayerClass)).getTarget().invokeExact();
       final BiConsumer<Object, Object> sendPacket = (BiConsumer<Object, Object>) LambdaMetafactory.metafactory(lookup,
                "accept",
                MethodType.methodType(BiConsumer.class),
                MethodType.methodType(void.class, Object.class, Object.class),
                lookup.findVirtual(playerConnectionClass, "sendPacket", MethodType.methodType(void.class, packetClass)),
                MethodType.methodType(void.class, playerConnectionClass, packetClass)).getTarget().invokeExact();
      if(titlePacketClass != null) {
        titlePacketClassAction = (Class<? extends Enum>) minecraftClass(serverVersion, "PacketPlayOutTitle$EnumTitleAction");
        titlePacketConstructor = titlePacketClass.getConstructor(titlePacketClassAction, baseComponentClass);
      } else {
        titlePacketClassAction = null;
        titlePacketConstructor = null;
      }
      final Class<?> chatSerializerClass = Arrays.stream(baseComponentClass.getClasses())
        .filter(JsonDeserializer.class::isAssignableFrom)
        .findAny()
        // fallback to the 1.7 class?
        .orElseGet(() -> {
          try {
            return minecraftClass(serverVersion, "ChatSerializer");
          } catch(final ClassNotFoundException e) {
            throw new RuntimeException(e);
          }
        });
      final Method serializeMethod = Arrays.stream(chatSerializerClass.getMethods())
        .filter(m -> Modifier.isStatic(m.getModifiers()))
        .filter(m -> m.getReturnType().equals(baseComponentClass))
        .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(String.class))
        .min(Comparator.comparing(Method::getName)) // prefer the #a method
        .orElseThrow(() -> new RuntimeException("Unable to find serialize method"));
      return new AliveBinding(playerHandle, playerConnectionField, sendPacket, chatPacketConstructor, titlePacketClassAction, titlePacketConstructor, serializeMethod);
    } catch(final Throwable e) {
      return new DeadBinding();
    }
  }

  private static boolean isCompatibleServer(final Class<?> serverClass) {
    return serverClass.getPackage().getName().startsWith("org.bukkit.craftbukkit")
      && serverClass.getSimpleName().equals("CraftServer");
  }

  private static Class<?> craftBukkitClass(final String version, final String name) throws ClassNotFoundException {
    return Class.forName("org.bukkit.craftbukkit." + version + name);
  }

  private static Class<?> minecraftClass(final String version, final String name) throws ClassNotFoundException {
    return Class.forName("net.minecraft.server." + version + name);
  }

  private static String maybeVersion(final String version) {
    if(version.isEmpty()) {
      return "";
    } else if(version.charAt(0) == '.') {
      return version.substring(1) + '.';
    }
    throw new IllegalArgumentException("Unknown version " + version);
  }

  private static Class<?> optionalMinecraftClass(final String version, final String name) {
    try {
      return minecraftClass(version, name);
    } catch(final ClassNotFoundException e) {
      return null;
    }
  }

  @Override
  public void sendMessage(final List<? extends CommandSender> viewers, final Component component) {
    if(!ALIVE) {
      return;
    }
    send(viewers, component, REFLECTION_BINDINGS::createMessagePacket);
  }

  @Override
  public void sendActionBar(final List<? extends CommandSender> viewers, final Component component) {
    if(!ALIVE) {
      return;
    }
    send(viewers, component, REFLECTION_BINDINGS::createActionBarPacket);
  }

  private static void send(final List<? extends CommandSender> viewers, final Component component, final Function<Component, Object> function) {
    Object packet = null;
    for(final Iterator<? extends CommandSender> iterator = viewers.iterator(); iterator.hasNext(); ) {
      final CommandSender sender = iterator.next();
      if(sender instanceof Player) {
        try {
          final Player player = (Player) sender;
          if(packet == null) {
            packet = function.apply(component);
          }
          REFLECTION_BINDINGS.sendPacket(packet, player);
          iterator.remove();
        } catch(final Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  private static abstract class Binding {
    abstract boolean valid();

    abstract Object createMessagePacket(final Component component);

    abstract Object createActionBarPacket(final Component component);

    abstract void sendPacket(final Object packet, final Player player);
  }

  private static final class DeadBinding extends Binding {
    @Override
    boolean valid() {
      return false;
    }

    @Override
    Object createMessagePacket(final Component component) {
      throw new UnsupportedOperationException();
    }

    @Override
    Object createActionBarPacket(final Component component) {
      throw new UnsupportedOperationException();
    }

    @Override
    void sendPacket(final Object packet, final Player player) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class AliveBinding extends Binding {
    private final Function<Player, Object> playerHandleFunction;
    private final Field playerConnectionField;
    private final BiConsumer<Object, Object> sendPacketFunction;
    private final Constructor<?> chatPacketConstructor;
    private final Class<? extends Enum> titlePacketClassAction;
    private final Constructor<?> titlePacketConstructor;
    private final boolean canMakeTitle;
    private final Method serializeMethod;

    AliveBinding(final Function<Player, Object> playerHandleFunction, final Field playerConnectionField, final BiConsumer<Object, Object> sendPacketFunction, final Constructor<?> chatPacketConstructor, final Class<? extends Enum> titlePacketClassAction, final Constructor<?> titlePacketConstructor, final Method serializeMethod) {
      this.playerHandleFunction = playerHandleFunction;
      this.playerConnectionField = playerConnectionField;
      this.sendPacketFunction = sendPacketFunction;
      this.chatPacketConstructor = chatPacketConstructor;
      this.titlePacketClassAction = titlePacketClassAction;
      this.titlePacketConstructor = titlePacketConstructor;
      this.canMakeTitle = this.titlePacketClassAction != null && this.titlePacketConstructor != null;
      this.serializeMethod = serializeMethod;
    }

    @Override
    boolean valid() {
      return true;
    }

    @Override
    Object createMessagePacket(final Component component) {
      final String json = GsonComponentSerializer.INSTANCE.serialize(component);
      try {
        return this.chatPacketConstructor.newInstance(this.serializeMethod.invoke(null, json));
      } catch(final Exception e) {
        throw new UnsupportedOperationException("An exception was encountered while creating a packet for a component", e);
      }
    }

    @Override
    Object createActionBarPacket(final Component component) {
      if(this.canMakeTitle) {
        try {
          Enum constant;
          try {
            constant = Enum.valueOf(this.titlePacketClassAction, "ACTIONBAR");
          } catch(final IllegalArgumentException e) {
            constant = this.titlePacketClassAction.getEnumConstants()[2];
          }
          final String json = GsonComponentSerializer.INSTANCE.serialize(component);
          return this.titlePacketConstructor.newInstance(constant, this.serializeMethod.invoke(null, json));
        } catch(final Exception e) {
          throw new UnsupportedOperationException("An exception was encountered while creating a packet for a component", e);
        }
      } else {
        return this.createMessagePacket(component);
      }
    }

    @Override
    void sendPacket(final Object packet, final Player player) {
      try {
          Object entityPlayer = this.playerHandleFunction.apply(player);
          this.sendPacketFunction.accept(this.playerConnectionField.get(entityPlayer), packet);
      } catch(final Exception e) {
        throw new UnsupportedOperationException("An exception was encountered while sending a packet for a component", e);
      }
    }
  }
}
