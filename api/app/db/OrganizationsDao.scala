package db

import com.gilt.apidoc.models._
import com.gilt.apidoc.models.json._
import lib.{Role, Validation, UrlKey}
import anorm._
import play.api.db._
import play.api.Play.current
import play.api.libs.json._
import java.util.UUID

object OrganizationsDao {

  private val MinNameLength = 4
  val MinKeyLength = 4
  val ReservedKeys = Seq(
    "_internal_", "account", "admin", "api", "api.json", "accept", "asset", "bucket",
    "code", "confirm", "config", "doc", "documentation", "domain", "email", "generator",
    "internal", "login", "logout", "member", "members", "metadatum", "metadata",
    "org", "password", "private", "reject", "session", "setting", "scms", "source", "subaccount",
    "subscription", "team", "user", "util", "version", "watch"
  ).map(UrlKey.generate(_))

  private val EmptyOrganizationMetadataForm = OrganizationMetadataForm()

  private[db] val BaseQuery = """
    select organizations.guid, organizations.name, organizations.key,
           organization_metadata.visibility as metadata_visibility,
           organization_metadata.package_name as metadata_package_name,
           (select array_to_string(array_agg(domain), ' ') 
              from organization_domains
             where deleted_at is null
               and organization_guid = organizations.guid) as domains
      from organizations
      left join organization_metadata on organization_metadata.deleted_at is null
                                     and organization_metadata.organization_guid = organizations.guid
     where organizations.deleted_at is null
  """

  def validate(form: OrganizationForm): Seq[com.gilt.apidoc.models.Error] = {
    val nameErrors = if (form.name.length < MinNameLength) {
      Seq(s"name must be at least $MinNameLength characters")
    } else {
      OrganizationsDao.findAll(Authorization.All, name = Some(form.name), limit = 1).headOption match {
        case None => Seq.empty
        case Some(org: Organization) => Seq("Org with this name already exists")
      }
    }

    val keyErrors = form.key match {
      case None => {
        nameErrors match {
          case Nil => {
            val generated = UrlKey.generate(form.name)
            ReservedKeys.find(prefix => generated.startsWith(prefix)) match {
              case None => Seq.empty
              case Some(prefix) => Seq(s"Prefix ${prefix} is a reserved word and cannot be used for the key of an organization")
            }
          }
          case errors => Seq.empty
        }
      }

      case Some(key) => {
        val generated = UrlKey.generate(key)
        if (key.length < MinKeyLength) {
          Seq(s"Key must be at least $MinKeyLength characters")
        } else if (key != generated) {
          Seq(s"Key must be in all lower case and contain alphanumerics only. A valid key would be: $generated")
        } else {
          ReservedKeys.find(prefix => generated.startsWith(prefix)) match {
            case Some(prefix) => Seq(s"Prefix $key is a reserved word and cannot be used for the key of an organization")
            case None => {
              OrganizationsDao.findByKey(Authorization.All, key) match {
                case None => Seq.empty
                case Some(existing) => Seq("Org with this key already exists")
              }
            }
          }
        }
      }
    }

    val domainErrors = form.domains.filter(!isDomainValid(_)).map(d => s"Domain $d is not valid. Expected a domain name like apidoc.me")

    Validation.errors(nameErrors ++ keyErrors ++ domainErrors)
  }


  // We just care that the domain does not have a space in it
  private val DomainRx = """^[^\s]+$""".r
  private[db] def isDomainValid(domain: String): Boolean = {
    domain match {
      case DomainRx() => true
      case _ => false
    }
  }

  /**
   * Creates the org and assigns the user as its administrator.
   */
  def createWithAdministrator(user: User, form: OrganizationForm): Organization = {
    val errors = validate(form)
    assert(errors.isEmpty, errors.map(_.message).mkString("\n"))

    DB.withTransaction { implicit c =>
      val org = create(c, user, form)
      MembershipsDao.create(c, user, org, user, Role.Admin)
      OrganizationLogsDao.create(c, user, org, s"Created organization and joined as ${Role.Admin.name}")
      org
    }
  }

  private[db] def emailDomain(email: String): Option[String] = {
    val parts = email.split("@")
    if (parts.length == 2) {
      Some(parts(1).toLowerCase)
    } else {
      None
    }
  }

  def findByEmailDomain(email: String): Option[Organization] = {
    emailDomain(email).flatMap { domain =>
      OrganizationDomainsDao.findAll(domain = Some(domain)).headOption.flatMap { domain =>
        findByGuid(Authorization.All, domain.organization_guid)
      }
    }
  }

  private[db] def reverseDomain(name: String): String = {
    name.split("\\.").reverse.mkString(".")
  }

  private def create(implicit c: java.sql.Connection, createdBy: User, form: OrganizationForm): Organization = {
    require(form.name.length >= MinNameLength, "Name too short")

    val defaultPackageName = form.domains.headOption.map(reverseDomain(_))

    val initialMetadataForm = form.metadata.getOrElse(EmptyOrganizationMetadataForm)
    val metadataForm = initialMetadataForm.packageName match {
      case None => initialMetadataForm.copy(packageName = defaultPackageName)
      case Some(_) => initialMetadataForm
    }

    val org = Organization(
      guid = UUID.randomUUID,
      key = form.key.getOrElse(UrlKey.generate(form.name)).trim,
      name = form.name.trim,
      domains = form.domains.map(Domain(_)),
      metadata = Some(
        OrganizationMetadata(
          packageName = metadataForm.packageName.map(_.trim)
        )
      )
    )

    SQL("""
      insert into organizations
      (guid, name, key, created_by_guid)
      values
      ({guid}::uuid, {name}, {key}, {created_by_guid}::uuid)
    """).on(
      'guid -> org.guid,
      'name -> org.name,
      'key -> org.key,
      'created_by_guid -> createdBy.guid
    ).execute()

    org.domains.foreach { domain =>
      OrganizationDomainsDao.create(c, createdBy, org, domain.name)
    }

    if (metadataForm != EmptyOrganizationMetadataForm) {
      OrganizationMetadataDao.create(c, createdBy, org, metadataForm)
    }

    org
  }

  def softDelete(deletedBy: User, org: Organization) {
    SoftDelete.delete("organizations", deletedBy, org.guid)
  }

  def findByGuid(authorization: Authorization, guid: UUID): Option[Organization] = {
    findAll(authorization, guid = Some(guid), limit = 1).headOption
  }

  def findByUserAndGuid(user: User, guid: UUID): Option[Organization] = {
    findByGuid(Authorization.User(user.guid), guid)
  }

  def findByUserAndKey(user: User, orgKey: String): Option[Organization] = {
    findByKey(Authorization.User(user.guid), orgKey)
  }

  def findByKey(authorization: Authorization, orgKey: String): Option[Organization] = {
    findAll(authorization, key = Some(orgKey), limit = 1).headOption
  }

  def findAll(
    authorization: Authorization,
    guid: Option[UUID] = None,
    userGuid: Option[UUID] = None,
    service: Option[Service] = None,
    key: Option[String] = None,
    name: Option[String] = None,
    limit: Long = 25,
    offset: Long = 0
  ): Seq[Organization] = {
    val sql = Seq(
      Some(BaseQuery.trim),
      authorization.organizationFilter("organizations.guid", Some("organization_metadata")).map(v => "and " + v),
      userGuid.map { v =>
        "and organizations.guid in (" +
        "select organization_guid from memberships where deleted_at is null and user_guid = {user_guid}::uuid" +
        ")"
      },
      service.map { v =>
        "and organizations.guid in (" +
        "select organization_guid from services where deleted_at is null and guid = {service_guid}::uuid" +
        ")"
      },
      guid.map { v => "and organizations.guid = {guid}::uuid" },
      key.map { v => "and organizations.key = lower(trim({key}))" },
      name.map { v => "and lower(trim(organizations.name)) = lower(trim({name}))" },
      Some(s"order by lower(organizations.name) limit ${limit} offset ${offset}")
    ).flatten.mkString("\n   ")

    val bind = Seq[Option[NamedParameter]](
      guid.map('guid -> _.toString),
      userGuid.map('user_guid -> _.toString),
      service.map('service_guid -> _.guid.toString),
      key.map('key -> _),
      name.map('name ->_)
    ).flatten ++ authorization.bindVariables

    DB.withConnection { implicit c =>
      SQL(sql).on(bind: _*)().toList.map { fromRow(_) }.toSeq
    }
  }

  private[db] def fromRow(
    row: anorm.Row
  ): Organization = {
    summaryFromRow(row).copy(
      domains = row[Option[String]]("domains").fold(Seq.empty[String])(_.split(" ")).sorted.map(Domain(_)),
      metadata = Some(
        OrganizationMetadata(
          visibility = row[Option[String]]("metadata_visibility").map(Visibility(_)),
          packageName = row[Option[String]]("metadata_package_name")
        )
      )
    )
  }

  private[db] def summaryFromRow(
    row: anorm.Row,
    prefix: Option[String] = None
  ): Organization = {
    val p = prefix.map( _ + "_").getOrElse("")

    Organization(
      guid = row[UUID](s"${p}guid"),
      key = row[String](s"${p}key"),
      name = row[String](s"${p}name")
    )
  }

}