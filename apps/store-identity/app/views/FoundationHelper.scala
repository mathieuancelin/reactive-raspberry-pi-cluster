package views

/**
 * Helper pour les input avec foundation
 * Created by adelegue on 09/06/2014.
 */
object FoundationHelper {
  import views.html.helper.FieldConstructor
  implicit val foundation = FieldConstructor(html.foundationFieldConstructorTemplate.f)
}
