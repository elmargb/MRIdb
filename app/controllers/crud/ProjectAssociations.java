package controllers.crud;

import play.mvc.With;
import controllers.CRUD;
import controllers.Check;
import controllers.Secure;

@With(Secure.class)
@Check("admin")
public class ProjectAssociations extends CRUD {
}
