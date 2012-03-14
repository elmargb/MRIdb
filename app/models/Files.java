package models;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import org.apache.commons.lang.StringUtils;

import play.db.jpa.GenericModel;

@Entity
public class Files extends GenericModel {

	@Id
	public long pk;
	public String filepath;
	public long file_size;
	@ManyToOne
	@JoinColumn(name="instance_fk")
	public Instance instance;

	public String toDownloadString() {
		return String.format("%s_%s", instance.series.toDownloadString(), StringUtils.leftPad(instance.inst_no, 3, '0'));
	}

	@Override
	public String toString() {
		return String.format("%s", pk);
	}
}
