package controllers;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import models.Patient;
import models.Series;
import models.Study;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.dcm4che.data.Dataset;

import play.Play;
import play.libs.Files;
import play.libs.IO;
import play.mvc.Before;
import util.Clipboard;
import util.Dicom;
import util.PersistentLogger;
import util.Properties;

public class Application extends SecureController {
	private static final String CLIPBOARD = "clipboard";

	@Before
	static void before() {
		renderArgs.put(CLIPBOARD, new Clipboard(session.get(CLIPBOARD)));
	}

	public static void index(String reset) throws Exception {
		//Query query = JPA.em().createNativeQuery("select * from study");
		//List results = query.getResultList();
		//System.out.println(Arrays.toString((Object[]) results.get(0)));

		//query = JPA.em().createNamedQuery("nativeSQL");
		//results = query.getResultList();
		//System.out.println(results);

		//for (Study study : Study.<Study>findAll()) {
		//	System.out.printf("%s\t%s\t%s\t%s\t%s%n", study.patient.pat_name, study.patient.pat_id, study.patient.pat_birthdate, study.study_desc, study.study_datetime);
		//}

		//System.out.println(Tags.toString(Tags.valueOf("(2005,140F)")));

		//		if (reset != null) {
		//			session.put(CLIPBOARD, "S51,s55");
		//		}

		render();
	}

	public static void help() throws Exception {
		render();
	}

	private static Map<String, String> comparators = new HashMap<String, String>() {{
		put("before", "<");
		put("on", "=");
		put("after", ">");
		put("since", ">");
	}};
	public static void studies(String name, String id, Integer age, Character sex, String protocol, String acquisition, String study) throws Exception {
		List<String> from = new ArrayList<String>();
		from.add("Study study");

		List<String> where = new ArrayList<String>();
		List<Object> args = new ArrayList<Object>();

		if (!name.isEmpty()) {
			where.add("lower(patient.pat_name) like ?");
			args.add("%" + name.toLowerCase() + "%");
		}
		if (!id.isEmpty()) {
			where.add("(lower(patient.pat_id) like ? or lower(study_custom1) like ?)");
			args.add("%" + id.toLowerCase() + "%");
			args.add("%" + id.toLowerCase() + "%");
		}
		if (age != null) {
			Calendar now = Calendar.getInstance();
			where.add("patient.pat_birthdate <= ? and patient.pat_birthdate > ?");
			now.add(Calendar.YEAR, -age);
			args.add(new SimpleDateFormat("yyyyMMdd").format(now.getTime()));
			now.add(Calendar.YEAR, -1);
			args.add(new SimpleDateFormat("yyyyMMdd").format(now.getTime()));
		}
		if (sex != null) {
			where.add("patient.pat_sex = ?");
			args.add(sex);
		}
		if (!protocol.isEmpty()) {
			from.add("in(study.series) series");
			where.add("lower(series.series_custom1) like ?");
			args.add("%" + protocol.toLowerCase() + "%");
		}
		if (!study.isEmpty()) {
			where.add("lower(study_desc) like ?");
			args.add("%" + study.toLowerCase() + "%");
		}
		if (!acquisition.isEmpty()) {
			where.add(String.format("cast(study_datetime as date) %s ?", comparators.get(acquisition)));
			args.add(params.get(acquisition, Date.class));
		}

		String query = "select study from " + StringUtils.join(from, ", ");
		if (!where.isEmpty()) {
			query += " where " + StringUtils.join(where, " and ");
		}
		List<Study> studies = Study.find(query, args.toArray()).fetch();
		render(studies);
	}

	public static void patient(long pk) throws Exception {
		Patient patient = Patient.findById(pk);
		render(patient);
	}

	public static void series(long pk) throws Exception {
		Series series = Series.findById(pk);
		Dataset dataset = Dicom.dataset(series);
		//Dataset privateDataset = Dicom.privateDataset(dataset);
		Set<Double> echoes = Dicom.echoes(dataset);
		render(series, dataset, echoes);
	}

	public static void image(String objectUID, Integer columns, Integer frameNumber) throws MalformedURLException, IOException {
		notFoundIfNull(objectUID);
		String url = String.format("http://%s:8080/wado?requestType=WADO&studyUID=&seriesUID=&objectUID=%s", Properties.getString("dicom.host"), objectUID);
		if (columns != null) {
			url += String.format("&columns=%s", columns);
		}
		if (frameNumber != null) {
			url += String.format("&frameNumber=%s", frameNumber);
		}
		IO.copy(new URL(url).openConnection().getInputStream(), response.out);
	}

	public static void download(long pk, String format) throws InterruptedException, IOException {
		PersistentLogger.log("Downloaded series %s", pk);
		File tmpDir = new File(new File(Play.tmpDir, "downloads"), UUID.randomUUID().toString());
		tmpDir.mkdir();
		Series series = Series.<Series>findById(pk);
		if (series.instances.size() == 1) {
			File dcm = Dicom.files(Series.<Series>findById(pk)).get(0);
			File anon = new File(tmpDir, String.format("%s.dcm", series.toDownloadString()));
			Dicom.anonymise(dcm, anon);
			//TODO check that ISD_dicom_tool doesn't handle anonymisation
			if ("nii".equals(format)) {
				File nii = new File(String.format("%s.nii", FilenameUtils.removeExtension(anon.getPath())));
				ProcessBuilder builder = new ProcessBuilder(new File(Play.applicationPath, "bin/dicom_2_nifti.py").getPath(), anon.getPath(), nii.getPath());
				builder.environment().put("PYTHONPATH", "/opt/pynifti-0.20100607.1:/opt/ISD_dicom_tool");
				builder.start().waitFor();
				renderBinary(nii);
			} else {
				renderBinary(anon);
			}
		} else {
			File dir = new File(tmpDir, series.series_iuid);
			dir.mkdir();
			if ("nii".equals(format)) {
				new ProcessBuilder(Properties.getString("dcm2nii"),
						"-d", "n",//don't put date in filename
						"-e", "n",//don't put series/acq in filename
						"-g", "n",//don't gzip
						"-i", "y",//use id in filename
						"-o", dir.getPath(),//don't put destination file in same directory as source
						"-p", "n",//don't put protocol in filename
						Dicom.folder(Series.<Series>findById(pk)).getPath()).start().waitFor();
				renderBinary(dir.listFiles()[0], String.format("%s.nii", series.toDownloadString()));
			} else {
				//TODO selective echo
				for (File dcm : Dicom.files(series)) {
					Dicom.anonymise(dcm, new File(dir, String.format("%s.dcm", dcm.getName())));
				}
				File zip = new File(tmpDir, String.format("%s.zip", series.toDownloadString()));
				Files.zip(dir, zip);
				renderBinary(zip);
			}
		}
	}

	public static void clipboard(String type, long pk) {
		session.put(CLIPBOARD, ((Clipboard) renderArgs.get(CLIPBOARD)).add(type, pk));
		redirect(request.headers.get("referer").value());
	}

	public static void unclipboard(Clipboard.Item item) {
		if (item == null) {
			session.put(CLIPBOARD, ((Clipboard) renderArgs.get(CLIPBOARD)).clear());
		} else {
			session.put(CLIPBOARD, ((Clipboard) renderArgs.get(CLIPBOARD)).remove(item));
		}
		redirect(request.headers.get("referer").value());
	}
}