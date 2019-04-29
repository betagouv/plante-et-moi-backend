package modules

import com.google.inject.AbstractModule
import services.TypeformService

class TypeformModule extends AbstractModule {
  override def configure() = {
    bind(classOf[TypeformService]).asEagerSingleton
  }
}
