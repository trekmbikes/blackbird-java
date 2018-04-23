package com.slickapps.blackbird.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Properties;

import com.slickapps.blackbird.model.Parameters;

public class ParametersDAO {

	static String findConfigFile(String fileName) throws FileNotFoundException {
		// local directory
		if (new File(fileName).exists())
			return fileName;

		// Unix user settings directory
		{
			String home = System.getProperty("user.home");
			String fullPath = home + File.separator + ".config" + File.separator + fileName;
			if (new File(fullPath).exists())
				return fullPath;
		}

		// Windows user settings directory
		String appData = System.getenv("APPDATA");
		if (appData != null) {
			String fullPath = appData + File.separator + fileName;
			if (new File(fullPath).exists())
				return fullPath;
		}

		{
			String fullPath = "/etc/" + fileName;
			if (new File(fullPath).exists())
				return fullPath;
		}

		throw new FileNotFoundException();
	}

	public static Parameters loadAndValidateParameters(String filename) throws Exception {
		Properties props = new Properties();
		props.load(new FileInputStream(findConfigFile(filename)));

		Parameters params = new Parameters();
		params.setFromProperties(props);

		if (params.targetProfitPercentage.signum() != 1)
			throw new Exception("TargetProfitPercentage should be positive");

		return params;
	}

}
