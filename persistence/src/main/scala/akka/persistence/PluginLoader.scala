package akka.persistence

import akka.actor.ExtendedActorSystem
import akka.util.Reflect

import scala.reflect.ClassTag
import scala.util.control.NonFatal

object PluginLoader {

  /**
    * Instantiate plugin of type [[T]] configured by path [[configPath]]
    * @param system akka system
    * @param configPath plugin config path
    * @tparam T plugin type
    * @return instance of plugin or thrown [[Exception]]
    */
  def loadPlugin[T: ClassTag](system: ExtendedActorSystem,
                              configPath: String): T = {

    val config = system.settings.config.getConfig(configPath)
    val className = config.getString("class")

    if (className.isEmpty)
      throw new IllegalArgumentException(
        s"Plugin class name must be defined in config property [$configPath.class]"
      )
    system.log.debug(s"Create plugin: $configPath $className")

    val clazz =
      system.dynamicAccess.getClassFor[T](className).get.asInstanceOf[Class[T]]

    try Reflect.instantiate[T](clazz, List(config, configPath))
    catch {
      case NonFatal(_) =>
        try Reflect.instantiate[T](clazz, List(config))
        catch {
          case NonFatal(_) => Reflect.instantiate[T](clazz, List.empty)
        }
    }
  }

}
