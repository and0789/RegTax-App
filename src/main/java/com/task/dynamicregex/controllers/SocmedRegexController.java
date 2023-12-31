package com.task.dynamicregex.controllers;

import com.task.dynamicregex.Main;
import com.task.dynamicregex.dao.ArtifactCategoryDao;
import com.task.dynamicregex.dao.SocmedRegexDao;
import com.task.dynamicregex.entities.ArtifactCategory;
import com.task.dynamicregex.entities.Result;
import com.task.dynamicregex.entities.SocmedRegex;
import com.task.dynamicregex.utils.Common;
import com.task.dynamicregex.utils.Helper;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SocmedRegexController implements Initializable {

    @FXML
    private Label titleLabel;
    @FXML
    private Label regexSelectedLabel;
    @FXML
    private TableView<SocmedRegex> socmedRegexTableView;
    @FXML
    private Button backButton;
    @FXML
    private Button addRegexButton;
    @FXML
    private Button editRegexButton;
    @FXML
    private Button removeRegexButton;
    @FXML
    private Button processButton;
    @FXML
    private TableColumn<SocmedRegex, CheckBox> selectTableColumn;
    @FXML
    private TableColumn<SocmedRegex, String> categoryTableColumn;
    @FXML
    private TableColumn<SocmedRegex, String> fieldTableColumn;
    @FXML
    private TableColumn<SocmedRegex, String> regexTableColumn;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label progressCountLabel;

    private ObservableList<SocmedRegex> socmedRegexList;
    private List<SocmedRegex> selectedSocmedRegexList;
    private boolean isSearchCancelled = false;
    private SocmedRegex selectedSocmedRegex;
    private SocmedRegexDao socmedRegexDao;
    private ArtifactCategoryDao artifactCategoryDao;
    private ObservableList<ArtifactCategory> artifactCategoryList;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        socmedRegexList = FXCollections.observableArrayList();
        socmedRegexDao = new SocmedRegexDao();
        selectedSocmedRegexList = FXCollections.observableArrayList();
        artifactCategoryDao = new ArtifactCategoryDao();
        artifactCategoryList = FXCollections.observableArrayList();

        try {
            socmedRegexList.addAll(socmedRegexDao.findAll(Common.SOCIALMEDIA.id()));
        } catch (SQLException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        CheckBox selectAllCheckBox = new CheckBox();
        selectAllCheckBox.setOnAction(actionEvent -> {
            if (selectAllCheckBox.isSelected()) {
                selectedSocmedRegexList.clear();
                selectedSocmedRegexList.addAll(socmedRegexList);
                processButton.setDisable(false);
            } else {
                selectedSocmedRegexList.clear();
            }
            processButton.setDisable(selectedSocmedRegexList.isEmpty());
            socmedRegexTableView.getItems().forEach(item -> item.getMark().setSelected(selectAllCheckBox.isSelected()));
            regexSelectedLabel.setText(selectedSocmedRegexList.size() + " regex selected");
        });

        titleLabel.setText(Common.SOCIALMEDIA.name() + " Regex");
        selectTableColumn.setGraphic(selectAllCheckBox);
        socmedRegexTableView.setItems(socmedRegexList);
        selectTableColumn.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().getMark()));
        categoryTableColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getArtifactCategory().getName()));
        fieldTableColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getField()));
        regexTableColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getRegex()));

        socmedRegexTableView.getItems().forEach(item -> item.getMark().setOnAction(actionEvent -> {
            if (item.getMark().isSelected()) {
                selectedSocmedRegexList.add(item);
            } else {
                selectAllCheckBox.setSelected(false);
                selectedSocmedRegexList.remove(item);
            }

            processButton.setDisable(selectedSocmedRegexList.isEmpty());
            boolean isSelectedAll = socmedRegexTableView.getItems().stream().map(SocmedRegex::getMark).toList().stream().allMatch(CheckBox::isSelected);
            selectAllCheckBox.setSelected(isSelectedAll);
            regexSelectedLabel.setText(selectedSocmedRegexList.size() + " regex selected");
        }));

    }

    @FXML
    private void addRegexButtonOnAction() {
        Helper.changePage(addRegexButton, "socmed-regex-add.fxml");
    }

    @FXML
    private void processButtonOnAction() {
        long startTime = System.currentTimeMillis();
        Task<ObservableList<Result>> task = new Task<>() {
            @Override
            protected ObservableList<Result> call() {
                ObservableList<Result> combinedResults = FXCollections.observableArrayList();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(Common.SELECTED_FILE), StandardCharsets.UTF_8))) {
                    ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                    List<Future<ObservableList<Result>>> futures = new ArrayList<>();
                    // char[] buffer = new char[8192];
                    char[] buffer = new char[2097152];
                    int bytesRead;
                    AtomicInteger resultsCount = new AtomicInteger();

                    while ((bytesRead = reader.read(buffer)) != -1) {
                        if (isSearchCancelled) {
                            executorService.shutdownNow();
                            return null;
                        }
                        String line = new String(buffer, 0, bytesRead);
                        Callable<ObservableList<Result>> task2 = () -> {
                            ObservableList<Result> results = FXCollections.observableArrayList();

                            for (SocmedRegex socmedRegex : selectedSocmedRegexList) {
                                Pattern pattern = Pattern.compile(socmedRegex.getRegex());
                                Matcher matcher = pattern.matcher(line);
                                while (matcher.find()) {
                                    resultsCount.getAndIncrement();
                                    Common.RESULT_COUNT = resultsCount.get();
                                    results.add(new Result(socmedRegex.getArtifactCategory().getName(), socmedRegex.getField(), matcher.group()));
                                    updateMessage(resultsCount + " results found");
                                }
                            }

                            return results;
                        };

                        Future<ObservableList<Result>> future = executorService.submit(task2);
                        futures.add(future);
                    }

                    for (Future<ObservableList<Result>> future : futures) {
                        ObservableList<Result> results = future.get();
                        combinedResults.addAll(results);
                    }

                    executorService.shutdown();
                } catch (IOException | RuntimeException | OutOfMemoryError | ExecutionException | InterruptedException e) {
                    throw new RuntimeException(e);
                }

                return combinedResults;
            }
        };

        task.setOnRunning(event -> {
            AnchorPane.setBottomAnchor(socmedRegexTableView, 140.0);
            progressBar.setVisible(true);
            progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            progressBar.setStyle("-fx-accent: #0C80D4");
            progressCountLabel.setVisible(true);
            progressCountLabel.setStyle("-fx-text-fill: black");
            backButton.setText("Cancel");
            addRegexButton.setDisable(true);
            processButton.setDisable(true);
            backButton.setOnAction(cancelEvent -> {
                ButtonType buttonTypeYes = new ButtonType("Yes");
                ButtonType buttonTypeNo = new ButtonType("No");

                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Confirmation");
                alert.setHeaderText("Cancel Search");
                alert.setContentText("Are you sure you want to cancel the search?");
                alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);
                alert.showAndWait();

                if (alert.getResult() == buttonTypeYes) {
                    isSearchCancelled = true;
                }
            });
        });

        task.setOnSucceeded(event -> {
            progressCountLabel.textProperty().unbind();
            if (!isSearchCancelled) {
                Common.THREADS = Runtime.getRuntime().availableProcessors();
                long endTime = System.currentTimeMillis();
                long elapsedTime = endTime - startTime;
                Common.ELAPSED_TIME = elapsedTime / 1000.0;
                Common.SELECTED_REGEX = selectedSocmedRegexList.size();
                Common.RESULTS = task.getValue();
                Helper.changePage(processButton, "result.fxml");
            } else {
                isSearchCancelled = false;
                backButton.setText("Back");
                backButton.setOnAction(this::backButtonOnAction);
                addRegexButton.setDisable(false);
                processButton.setDisable(false);
                progressBar.setProgress(1);
                progressBar.setStyle("-fx-accent: #FF3F3F");
                progressCountLabel.setStyle("-fx-text-fill: white");
                progressCountLabel.setText("Search cancelled");
            }
        });

        task.setOnFailed(event -> {
            progressBar.setProgress(1);
            progressBar.setStyle("-fx-accent: #FF3F3F");
            progressCountLabel.setStyle("-fx-text-fill: white");
            progressCountLabel.textProperty().unbind();
            progressCountLabel.setText("Search failed");
            backButton.setText("Back");
            backButton.setOnAction(this::backButtonOnAction);

            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Search failed");
            alert.setContentText(task.getException().getMessage());
            alert.showAndWait();

            task.cancel(true);
        });

        progressCountLabel.textProperty().bind(task.messageProperty());

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void backButtonOnAction(ActionEvent actionEvent) {
        Helper.changePage(backButton, "social-media.fxml");
    }

    @FXML
    private void editRegexButtonOnAction() throws IOException, SQLException, ClassNotFoundException {
        Optional<SocmedRegex> dialogResult = showEditDialog(selectedSocmedRegex);
        if (dialogResult.isPresent() && socmedRegexDao.update(dialogResult.get()) == 1) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Success");
            alert.setHeaderText("Regex successfully edited");
            alert.getDialogPane().getStylesheets().add(Objects.requireNonNull(Main.class.getResource("/com/task/dynamicregex/style.css")).toExternalForm());
            alert.showAndWait();
            refreshTableView();
        }
    }

    @FXML
    private void removeRegexButtonOnAction() throws SQLException, ClassNotFoundException {
        Optional<ButtonType> dialogResult = showConfirmAlert();
        if (dialogResult.isPresent() && dialogResult.get() == ButtonType.OK &&
                socmedRegexDao.delete(selectedSocmedRegex) == 1) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Success");
            alert.setHeaderText("Regex successfully removed");
            alert.getDialogPane().getStylesheets().add(Objects.requireNonNull(Main.class.getResource("/com/task/dynamicregex/style.css")).toExternalForm());
            alert.showAndWait();
            refreshTableView();
        }
    }

    @FXML
    private void tableViewOnMouseClicked() {
        selectedSocmedRegex = socmedRegexTableView.getSelectionModel().getSelectedItem();
        if (selectedSocmedRegex != null) {
            editRegexButton.setVisible(true);
            removeRegexButton.setVisible(true);
        }
    }

    @FXML
    private void tableViewOnKeyReleased(KeyEvent keyEvent) {
        selectedSocmedRegex = socmedRegexTableView.getSelectionModel().getSelectedItem();

        if (selectedSocmedRegex == null) {
            return;
        }

        editRegexButton.setVisible(true);
        removeRegexButton.setVisible(true);

        if (keyEvent.getCode() == KeyCode.ESCAPE) {
            selectedSocmedRegex = null;
            editRegexButton.setVisible(false);
            removeRegexButton.setVisible(false);
            socmedRegexTableView.getSelectionModel().clearSelection();
        }

        socmedRegexTableView.getSelectionModel().selectedItemProperty()
                .addListener((observableValue, socmedRegex, t1) -> selectedSocmedRegex = socmedRegexTableView.getSelectionModel().getSelectedItem());
    }

    private void refreshTableView() throws SQLException, ClassNotFoundException {
        socmedRegexList.clear();
        socmedRegexList.addAll(socmedRegexDao.findAll(Common.SOCIALMEDIA.id()));
        socmedRegexTableView.setItems(socmedRegexList);
        selectedSocmedRegex = null;
        editRegexButton.setVisible(false);
        removeRegexButton.setVisible(false);
        socmedRegexTableView.getSelectionModel().clearSelection();

    }

    private Optional<SocmedRegex> showEditDialog(SocmedRegex socmedRegex) throws IOException, SQLException, ClassNotFoundException {
        artifactCategoryList.clear();
        artifactCategoryList.addAll(artifactCategoryDao.findAll(Common.SOCIALMEDIA.id()));

        FXMLLoader fxmlLoader = new FXMLLoader(Main.class.getResource("regex-edit-dialog.fxml"));
        DialogPane dialogPane = fxmlLoader.load();

        TextField fieldTextField = (TextField) dialogPane.lookup("#fieldTextField");
        fieldTextField.setText(socmedRegex.getField());
        TextField regexTextField = (TextField) dialogPane.lookup("#regexTextField");
        regexTextField.setText(socmedRegex.getRegex());
        ComboBox<ArtifactCategory> categoryComboBox = (ComboBox<ArtifactCategory>) dialogPane.lookup("#categoryComboBox");
        categoryComboBox.setItems(artifactCategoryList);
        categoryComboBox.setValue(socmedRegex.getArtifactCategory());

        Platform.runLater(() -> {
            fieldTextField.requestFocus();
            fieldTextField.positionCaret(fieldTextField.getText().length());
            fieldTextField.deselect();
        });

        Dialog<SocmedRegex> dialog = new Dialog<>();
        dialog.setDialogPane(dialogPane);
        dialog.setTitle("Edit");
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).getStyleClass().add("red");

        fieldTextField.setOnKeyReleased(keyEvent ->
                dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(fieldTextField.getText().isBlank()));

        regexTextField.setOnKeyReleased(keyEvent ->
                dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(regexTextField.getText().isBlank()));

        categoryComboBox.setOnAction(action ->
                dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(categoryComboBox.getValue() == null));

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                return new SocmedRegex(
                        socmedRegex.getId(),
                        fieldTextField.getText().trim(),
                        regexTextField.getText().trim(),
                        categoryComboBox.getValue());
            }
            return null;
        });

        return dialog.showAndWait();
    }

    private Optional<ButtonType> showConfirmAlert() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirmation");
        alert.setHeaderText("Are you sure you want to remove \"" + selectedSocmedRegex.getField() + "\"?");
        alert.getDialogPane().getStylesheets().add(Objects.requireNonNull(Main.class.getResource("/com/task/dynamicregex/style.css")).toExternalForm());
        alert.getDialogPane().lookupButton(ButtonType.CANCEL).getStyleClass().add("red");
        return alert.showAndWait();
    }
}
